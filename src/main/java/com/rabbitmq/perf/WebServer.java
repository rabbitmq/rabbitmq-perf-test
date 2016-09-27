// Copyright (c) 2007-Present Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.perf;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class WebServer {

    public static void main(String[] args) throws Exception {
        if(args.length != 0 && args.length != 2) {
            System.out.println("Usage: WebServer [baseDirectory] [port]");
            System.exit(1);
        }

        String baseDirectory = "./html";
        int port = 8080;
        if(args.length == 2) {
            baseDirectory = args[0];
            port = Integer.valueOf(args[1]);
        }

        System.setProperty("org.eclipse.jetty.LEVEL", "WARN");
        Server server = new Server(port);

        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setResourceBase(baseDirectory);
        context.setContextPath("/");
        server.setHandler(context);

        ServletHolder holderPwd = new ServletHolder("default", DefaultServlet.class);
        holderPwd.setInitParameter("dirAllowed", "true");
        context.addServlet(holderPwd, "/");

        server.start();
    }

}
