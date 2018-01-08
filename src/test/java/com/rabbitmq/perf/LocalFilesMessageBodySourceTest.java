// Copyright (c) 2017-Present Pivotal Software, Inc.  All rights reserved.
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

import org.junit.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@EnableRuleMigrationSupport
public class LocalFilesMessageBodySourceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void createOneFileThatExists() throws Exception {
        File file = folder.newFile("content.txt");
        String content = "dummy content";
        write(file, content);
        MessageBodySource creator = new LocalFilesMessageBodySource(asList(file.getAbsolutePath()));
        byte[] body1 = creator.create(1).getBody();
        byte[] body2 = creator.create(1).getBody();
        assertEquals(content, new String(body1, "UTF-8"));
        assertEquals(content, new String(body2, "UTF-8"));
    }

    @Test public void createSeveralFileThatExists() throws Exception {
        List<String> files = new ArrayList<String>();
        for(int i = 0; i < 3 ; i++) {
            File file = folder.newFile("content" + i +".txt");
            String content = "content" + i;
            write(file, content);
            files.add(file.getAbsolutePath());
        }

        MessageBodySource creator = new LocalFilesMessageBodySource(files);
        byte[] body0 = creator.create(0).getBody();
        assertEquals("content0", new String(body0, "UTF-8"));
        byte[] body1 = creator.create(1).getBody();
        assertEquals("content1", new String(body1, "UTF-8"));
        byte[] body2 = creator.create(2).getBody();
        assertEquals("content2", new String(body2, "UTF-8"));
        byte[] body4 = creator.create(3).getBody();
        assertEquals("content0", new String(body4, "UTF-8"));
    }

    @Test public void createFileDoesNotExist() throws Exception {
        File file = new File(folder.getRoot(), "dummy.txt");
        try {
            new LocalFilesMessageBodySource(asList(file.getAbsolutePath()));
            fail("File does not exist, exception should have thrown");
        } catch (IllegalArgumentException e) {
            // ok
        }
    }

    private static void write(File file, String content) throws Exception {
        FileWriter writer = new FileWriter(file);
        writer.append(content);
        writer.flush();
        writer.close();
    }

}
