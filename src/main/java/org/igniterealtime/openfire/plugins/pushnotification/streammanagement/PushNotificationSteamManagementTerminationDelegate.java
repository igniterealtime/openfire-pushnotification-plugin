/*
 * Copyright (C) 2025 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.igniterealtime.openfire.plugins.pushnotification.streammanagement;

import org.jivesoftware.openfire.streammanagement.TerminationDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;

/**
 * Determines if a detached session (in context of Stream Management) can be terminated based on push notifications.
 *
 * For a user that has push notifications enabled, a session is allowed to be terminated only after a first push
 * notification has been sent. The rationale for this is that some clients (notably IOS-based clients) are not able to
 * maintain a connection to the server while running in the background (which can happen as often as every 30 seconds).
 * To terminate such clients would lead to a considerable amount of session lifecycles. This implementation considers
 * that such clients would react almost instantly to a push notification. Thus, SM termination is appropriate when a
 * client remains inactive after it was sent the first push notification.
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 */
public class PushNotificationSteamManagementTerminationDelegate implements TerminationDelegate
{
    private static final Logger Log = LoggerFactory.getLogger(PushNotificationSteamManagementTerminationDelegate.class);

    private Instant oldestUnansweredPushNotification;

    @Override
    public synchronized boolean shouldTerminate(@Nonnull final Duration allowableInactivity)
    {
        final boolean result = oldestUnansweredPushNotification != null && oldestUnansweredPushNotification.isBefore(Instant.now().minus(allowableInactivity));
        Log.trace("Should terminate: {} (Oldest unanswered notification: {} - Allowable inactivity: {})", (result ? "yes" : "no"), oldestUnansweredPushNotification, allowableInactivity);
        return result;
    }

    public synchronized void registerActivity() {
        oldestUnansweredPushNotification = null;
    }

    public synchronized void registerPushNotification() {
        if (oldestUnansweredPushNotification == null) {
            oldestUnansweredPushNotification = Instant.now();
        }
    }
}
