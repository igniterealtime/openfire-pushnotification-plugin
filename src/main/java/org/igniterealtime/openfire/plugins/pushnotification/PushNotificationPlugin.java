/*
 * Copyright (C) 2019 Ignite Realtime Foundation. All rights reserved.
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
package org.igniterealtime.openfire.plugins.pushnotification;

import org.jivesoftware.openfire.OfflineMessageStrategy;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * An Openfire plugin that adds push notification support, as defined in XEP-0357.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 * @see <a href="https://xmpp.org/extensions/xep-0357.html">XEP-0357: "Push Notifications"</a>
 */
public class PushNotificationPlugin implements Plugin
{
    private static final Logger Log = LoggerFactory.getLogger( PushNotificationPlugin.class );

    private final List<IQHandler> registeredHandlers = new ArrayList<>();

    private final PushInterceptor interceptor = new PushInterceptor();

    /**
     * Initializes the plugin.
     *
     * @param manager         the plugin manager.
     * @param pluginDirectory the directory where the plugin is located.
     */
    @Override
    public synchronized void initializePlugin( final PluginManager manager, final File pluginDirectory )
    {
        Log.debug( "Initializing..." );

        final Push0IQHandler push0IQHandler = new Push0IQHandler();
        XMPPServer.getInstance().getIQRouter().addHandler( push0IQHandler );
        registeredHandlers.add( push0IQHandler );

        InterceptorManager.getInstance().addInterceptor( interceptor );
        OfflineMessageStrategy.addListener( interceptor );

        XMPPServer.getInstance().getIQDiscoInfoHandler().addServerFeature( Push0IQHandler.ELEMENT_NAMESPACE );

        Log.debug( "Initialized." );
    }

    /**
     * Destroys the plugin.<p>
     * <p>
     * Implementations of this method must release all resources held
     * by the plugin such as file handles, database or network connections,
     * and references to core Openfire classes. In other words, a
     * garbage collection executed after this method is called must be able
     * to clean up all plugin classes.
     */
    @Override
    public synchronized void destroyPlugin()
    {
        Log.debug( "Destroying..." );

        XMPPServer.getInstance().getIQDiscoInfoHandler().removeServerFeature( Push0IQHandler.ELEMENT_NAMESPACE );

        final Iterator<IQHandler> iterator = registeredHandlers.iterator();
        while ( iterator.hasNext() )
        {
            final IQHandler registeredHandler = iterator.next();
            try
            {
                XMPPServer.getInstance().getIQRouter().removeHandler( registeredHandler );
            }
            catch ( Exception e )
            {
                Log.warn( "An unexpected exception occurred while trying to remove the handler for {}.", registeredHandler.getInfo(), e );
            }
            finally
            {
                iterator.remove();
            }
        }

        OfflineMessageStrategy.removeListener( interceptor );
        InterceptorManager.getInstance().removeInterceptor( interceptor );

        Log.debug( "Destroyed." );
    }
}
