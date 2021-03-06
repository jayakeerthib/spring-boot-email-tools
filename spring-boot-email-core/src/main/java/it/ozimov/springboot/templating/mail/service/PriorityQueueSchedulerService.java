/*
 * Copyright 2012-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.ozimov.springboot.templating.mail.service;

import it.ozimov.springboot.templating.mail.model.Email;
import it.ozimov.springboot.templating.mail.model.EmailSchedulingWrapper;
import it.ozimov.springboot.templating.mail.model.EmailTemplateSchedulingWrapper;
import it.ozimov.springboot.templating.mail.model.InlinePicture;
import it.ozimov.springboot.templating.mail.service.exception.CannotSendEmailException;
import it.ozimov.springboot.templating.mail.utils.TimeUtils;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

@Service
@Slf4j
public class PriorityQueueSchedulerService implements SchedulerService {

    /**
     * millisecs elapsed form the call of the send method and the actual sending by SMTP server
     */
    private static final long DELTA = SECONDS.toMillis(1);
    private ServiceStatus serviceStatus = ServiceStatus.CREATED;
    private Long timeOfNextScheduledMessage;

    private TreeSet<EmailSchedulingWrapper>[] queues;

    private EmailService emailService;

    private Consumer consumer;

    @Autowired
    public PriorityQueueSchedulerService(
            final EmailService emailService,
            @Value("${spring.mail.scheduler.priorityLevels:10}") final int numberOfPriorityLevels) {
        checkArgument(numberOfPriorityLevels > 0, "Expected at least one priority level");
        this.emailService = requireNonNull(emailService);
        queues = new TreeSet[numberOfPriorityLevels];
        for (int i = 0; i < numberOfPriorityLevels; i++) {
            queues[i] = new TreeSet<>();
        }
        consumer = new Consumer();
        consumer.start();
    }

    @Override
    public synchronized void schedule(@NonNull final Email mimeEmail, @NonNull final OffsetDateTime scheduledDateTime,
                                      final int priorityLevel) {
        checkPriorityLevel(priorityLevel);

        final int realPriorityLevel = normalizePriority(priorityLevel);
        final EmailSchedulingWrapper emailSchedulingWrapper = new EmailSchedulingWrapper(mimeEmail, scheduledDateTime, realPriorityLevel);
        queues[priorityLevel - 1].add(emailSchedulingWrapper);
        log.info("Scheduled email {} at UTC time {} with priority {}", mimeEmail, scheduledDateTime, priorityLevel);
        if (isNull(timeOfNextScheduledMessage) || scheduledDateTime.toInstant().toEpochMilli() < timeOfNextScheduledMessage) {
            notify(); //the consumer, if waiting, is notified and can try to send next scheduled message
        }
    }

    @Override
    public synchronized void schedule(@NonNull final Email mimeEmail,
                                      @NonNull final OffsetDateTime scheduledDateTime,
                                      final int priorityLevel,
                                      @NonNull final String template,
                                      @NonNull final Map<String, Object> modelObject,
                                      final InlinePicture... inlinePictures) throws CannotSendEmailException {
        checkPriorityLevel(priorityLevel);

        final int realPriorityLevel = normalizePriority(priorityLevel);
        final EmailTemplateSchedulingWrapper emailTemplateSchedulingWrapper = new EmailTemplateSchedulingWrapper(mimeEmail, scheduledDateTime, realPriorityLevel,
                template, modelObject, inlinePictures);
        queues[priorityLevel - 1].add(emailTemplateSchedulingWrapper);
        log.info("Scheduled email {} at UTC time {} with priority {} with template", mimeEmail, scheduledDateTime, priorityLevel);
        if (isNull(timeOfNextScheduledMessage) || scheduledDateTime.toInstant().toEpochMilli() < timeOfNextScheduledMessage) {
            notify();
        }
    }

    public synchronized ServiceStatus status() {
        return serviceStatus;
    }

    private int normalizePriority(int priorityLevel) {
        //the priority level must be between 0 and numberOfPriorityLevels
        final int maxLevel = queues.length;
        if (priorityLevel > maxLevel) {
            log.warn("Scheduled email with priority level {}, while priority level {} was requested. Reason: max level exceeded",
                    maxLevel, priorityLevel);
        }
        return max(1, min(priorityLevel, maxLevel));
    }

    private synchronized Optional<EmailSchedulingWrapper> dequeue() throws InterruptedException {
        EmailSchedulingWrapper emailSchedulingWrapper = null;
        timeOfNextScheduledMessage = null;
        while (consumer.enabled() && isNull(emailSchedulingWrapper)) {
            //try to find a message in queue
            final long now = TimeUtils.now();
            for (final TreeSet<EmailSchedulingWrapper> queue : queues) {
                if (!queue.isEmpty()) {
                    final long time = queue.first().getScheduledDateTime().toInstant().toEpochMilli();
                    if (time - now <= DELTA) {
                        //message found!
                        emailSchedulingWrapper = queue.pollFirst();
                        break;
                    } else if (isNull(timeOfNextScheduledMessage) || time < timeOfNextScheduledMessage) {
                        timeOfNextScheduledMessage = time;
                    }

                }
            }
            if (isNull(emailSchedulingWrapper)) {
                //no message was found, let's sleep, some message may arrive in the meanwhile
                if (isNull(timeOfNextScheduledMessage)) { //all the queues are empty
                    wait(); //the consumer starts waiting for a new email to be scheduled
                } else {
                    final long waitTime = timeOfNextScheduledMessage - TimeUtils.now() - DELTA;
                    if (waitTime > 0) {
                        wait(waitTime); //wait before sending the most imminent scheduled email
                    }
                }
            }
        }
        //here emailSchedulingWrapper is the message to send
        return Optional.ofNullable(emailSchedulingWrapper);
    }

    private void checkPriorityLevel(int priorityLevel) {
        checkArgument(priorityLevel > 0, "The priority level index cannot be negative");
    }

    @PreDestroy
    protected synchronized void cleanUp() throws Exception {
        consumer.close();
        serviceStatus = ServiceStatus.CLOSED;
        notify(); //the consumer, if waiting, is notified and can try to close
    }

    class Consumer extends Thread {

        private boolean canRun = true;

        public void run() {
            log.info("Email scheduler consumer started");
            while (canRun) {
                try {
                    final Optional<EmailSchedulingWrapper> emailSchedulingWrapperOptional = dequeue();
                    if (canRun && emailSchedulingWrapperOptional.isPresent()) {
                        final EmailSchedulingWrapper emailSchedulingWrapper = emailSchedulingWrapperOptional.get();
                        if (emailSchedulingWrapper instanceof EmailTemplateSchedulingWrapper) {
                            final EmailTemplateSchedulingWrapper emailTemplateSchedulingWrapper =
                                    (EmailTemplateSchedulingWrapper) emailSchedulingWrapper;
                            try {
                                emailService.send(emailTemplateSchedulingWrapper.getEmail(),
                                        emailTemplateSchedulingWrapper.getTemplate(),
                                        emailTemplateSchedulingWrapper.getModelObject(),
                                        emailTemplateSchedulingWrapper.getInlinePictures());
                            } catch (final CannotSendEmailException e) {
                                log.error("An error occurred while sending the email", e);
                            }
                        } else {
                            emailService.send(emailSchedulingWrapper.getEmail());
                        }
                    } else {
                        log.info("Email scheduler consumer stopped");
                    }
                } catch (final InterruptedException e) {
                    log.error("Email scheduler consumer interrupted", e);
                }
            }
            log.info("Email scheduler consumer stopped");
        }

        public synchronized boolean enabled() {
            return canRun;
        }

        public synchronized void close() {
            canRun = false;
        }

    }

}