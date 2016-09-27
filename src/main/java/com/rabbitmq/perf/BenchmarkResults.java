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
import org.eclipse.jetty.util.resource.Resource;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;

public class BenchmarkResults {

    public static void main(String[] args) throws Exception {
        if(args.length != 1) {
            System.out.println("Usage: BenchmarkResults result-json-file");
            System.exit(1);
        }
        BufferedReader reader = null;
        StringBuilder builder = new StringBuilder();
        try {
            File resultsFile = new File(args[0]);
            reader = new BufferedReader(new FileReader(resultsFile));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        } finally {
            if(reader != null) {
                reader.close();
            }
        }

        System.setProperty("org.eclipse.jetty.LEVEL", "WARN");
        Server server = new Server(8080);

        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setBaseResource(Resource.newClassPathResource("/static"));
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(new ServletHolder(new JsonServlet(builder.toString())), "/data");

        ServletHolder holderPwd = new ServletHolder("default", DefaultServlet.class);
        holderPwd.setInitParameter("dirAllowed", "true");
        context.addServlet(holderPwd, "/");

        server.start();

        Desktop.getDesktop().browse(new URI("http://localhost:8080/index.html"));
    }

    public static class JsonServlet extends HttpServlet {

        private final String content;

        public JsonServlet(String content) {
            this.content = content;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.getWriter().append(content);
            resp.getWriter().flush();
        }
    }
}
