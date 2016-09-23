package com.rabbitmq.examples;

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

/**
 *
 */
public class BenchmarkResults {

    public static void main(String[] args) throws Exception {
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

        Desktop.getDesktop().browse(new URI("http://localhost:8080/examples/sample.html"));
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
