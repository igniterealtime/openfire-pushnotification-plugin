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

import org.igniterealtime.openfire.plugins.pushnotification.PushServiceManager;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.event.SessionEventListener;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.LocalClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Packet;

import javax.annotation.Nonnull;
import java.sql.SQLException;

/**
 * Responsible for managing instances of {@link PushNotificationSteamManagementTerminationDelegate} on all applicable
 * client sessions.
 *
 * Instances are stored as a sessionData on the session object itself, using the key defined by {@link #PUSHNOTIFICATION_TERMINATION_DELEGATE}
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 */
public class TerminationDelegateManager implements SessionEventListener, PacketInterceptor
{
    private static final Logger Log = LoggerFactory.getLogger(TerminationDelegateManager.class);

    public static final String PUSHNOTIFICATION_TERMINATION_DELEGATE = "pushnotification.terminationDelegate";

    @Override
    public void sessionCreated(Session session)
    {
        if (!(session instanceof LocalClientSession)) {
            return;
        }
        final LocalClientSession clientSession = (LocalClientSession) session;

        // If the user has push notification enabled, register the delegate.
        final boolean hasPushEnabled = doesUserHavePushEnabled(clientSession);
        if (hasPushEnabled) {
            registerDelegate(clientSession);
        }
    }

    @Override
    public void sessionDestroyed(Session session)
    {
        if (!(session instanceof LocalClientSession)) {
            return;
        }

        // Remove a delegate (if one was active).
        deregisterDelegate((LocalClientSession) session);
    }

    public static void registerDelegate(final LocalClientSession clientSession) {
        if (clientSession.getSessionData(PUSHNOTIFICATION_TERMINATION_DELEGATE) == null) {
            Log.trace("Registering delegate for {}", clientSession);
            final PushNotificationSteamManagementTerminationDelegate delegate = new PushNotificationSteamManagementTerminationDelegate();
            clientSession.setSessionData(PUSHNOTIFICATION_TERMINATION_DELEGATE, delegate);
            clientSession.getStreamManager().addTerminationDelegate(delegate);
        } else {
            Log.trace("Skip registering delegate (one already is registered) for {}", clientSession);
        }
    }

    public static void deregisterDelegate(final LocalClientSession clientSession) {
        final PushNotificationSteamManagementTerminationDelegate delegate = (PushNotificationSteamManagementTerminationDelegate) clientSession.removeSessionData(PUSHNOTIFICATION_TERMINATION_DELEGATE);
        if (delegate != null) {
            Log.trace("Deregistering delegate for {}", clientSession);
            clientSession.getStreamManager().removeTerminationDelegate(delegate);
        }
    }

    public static void registerDelegateFor(@Nonnull final User user) {
        Log.trace("Registering delegate for all sessions of {}", user);
        SessionManager.getInstance().getSessions(user.getUsername()).stream()
            .filter(session -> session instanceof LocalClientSession)
            .map(session -> (LocalClientSession) session)
            .forEach(TerminationDelegateManager::registerDelegate);
    }

    public static void deregisterDelegateFor(@Nonnull final User user) {
        Log.trace("Deregistering delegate for all sessions of {}", user);
        SessionManager.getInstance().getSessions(user.getUsername()).stream()
            .filter(session -> session instanceof LocalClientSession)
            .map(session -> (LocalClientSession) session)
            .forEach(TerminationDelegateManager::deregisterDelegate);
    }

    public static void registerDelegateForAll() {
        Log.debug("Registering delegate for all sessions...");
        SessionManager.getInstance().getSessions().stream()
            .filter(session -> session instanceof LocalClientSession)
            .map(session -> (LocalClientSession) session)
            .forEach(session -> {
                final boolean hasPushEnabled = doesUserHavePushEnabled(session);
                if (hasPushEnabled) {
                    registerDelegate(session);
                }
            });
        Log.debug("Done registering delegate for all sessions.");
    }

    public static void deregisterDelegateForAll() {
        Log.debug("Deregistering delegate for all sessions...");
        SessionManager.getInstance().getSessions().stream()
            .filter(session -> session instanceof LocalClientSession)
            .map(session -> (LocalClientSession) session)
            .forEach(TerminationDelegateManager::deregisterDelegate);
        Log.debug("Done deregistering delegate for all sessions.");
    }

    public static void registerActivityFor(@Nonnull final LocalClientSession clientSession) {
        final PushNotificationSteamManagementTerminationDelegate delegate = (PushNotificationSteamManagementTerminationDelegate) clientSession.getSessionData(PUSHNOTIFICATION_TERMINATION_DELEGATE);
        if (delegate != null) {
            delegate.registerActivity();
        }
    }

    public static void registerPushNotificationFor(@Nonnull final User user) {
        SessionManager.getInstance().getSessions(user.getUsername()).stream()
            .filter(session -> session instanceof LocalClientSession)
            .map(session -> (LocalClientSession) session)
            .forEach(session -> {
                final PushNotificationSteamManagementTerminationDelegate delegate = (PushNotificationSteamManagementTerminationDelegate) session.getSessionData(PUSHNOTIFICATION_TERMINATION_DELEGATE);
                if (delegate != null) {
                    delegate.registerPushNotification();
                }
            });
    }

    public static boolean doesUserHavePushEnabled(final User user) {
        try {
            return PushServiceManager.hasServiceNodes(user);
        } catch (SQLException e) {
            Log.warn("A database problem prevented a check to see if user {} has push notifications enabled.", user, e);
        }
        return false;
    }

    public static boolean doesUserHavePushEnabled(final ClientSession clientSession) {
        try {
            final String username = clientSession.getUsername();
            final User user = UserManager.getInstance().getUser(username);
            return PushServiceManager.hasServiceNodes(user);
        } catch (UserNotFoundException e) {
            Log.warn("Unable to perform a check to see if the user related to this session has push notifications enabled: {}", clientSession, e);
        } catch (SQLException e) {
            Log.warn("A database problem prevented a check to see if the user related to this session has push notifications enabled: {}", clientSession, e);
        }
        return false;
    }

    @Override
    public void interceptPacket(final Packet packet, final Session session, final boolean incoming, final boolean processed) throws PacketRejectedException
    {
        if (!(session instanceof LocalClientSession)) {
            return;
        }

        final boolean inbound = incoming && !processed; // pre or post processed probably doesn't matter, but we don't want to fire twice.
        if (!inbound) {
            return;
        }

        registerActivityFor((LocalClientSession) session);
    }

    @Override
    public void anonymousSessionCreated(Session session) {}

    @Override
    public void anonymousSessionDestroyed(Session session) {}

    @Override
    public void resourceBound(Session session) {}
}
