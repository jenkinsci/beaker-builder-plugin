/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.beakerbuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import hudson.model.Item;
import hudson.model.FreeStyleProject;

import org.jenkinsci.plugins.beakerbuilder.BeakerBuilder.DescriptorImpl;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import org.jvnet.hudson.test.recipes.LocalData;

import java.io.IOException;

public class ConfigRoundtripTest {

    public @Rule JenkinsRule j = new JenkinsRule();

    @Test
    public void builderRoundtrip() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();

        final FileJobSource fileSource = new FileJobSource("my.file");
        final StringJobSource stringSource = new StringJobSource("<job>");

        p.getBuildersList().add(new BeakerBuilder(fileSource, true));
        p.getBuildersList().add(new BeakerBuilder(stringSource, false));

        j.configRoundtrip((Item) p);

        BeakerBuilder file = (BeakerBuilder) p.getBuildersList().get(0);
        BeakerBuilder string = (BeakerBuilder) p.getBuildersList().get(1);

        assertEquals("my.file", ((FileJobSource) file.getJobSource()).getJobPath());
        assertTrue(file.getDownloadFiles());
        assertEquals("<job>", ((StringJobSource) string.getJobSource()).getJobContent());
        assertFalse(string.getDownloadFiles());
    }

    @Test
    public void connectionRoundtrip() throws Exception {
        WebClient wc = j.createWebClient();
        HtmlPage page = wc.goTo("configure");
        HtmlForm form = page.getFormByName("config");
        form.getInputByName("_.beakerURL").setValueAttribute("URL");
        form.getInputByName("_.login").setValueAttribute("USERNAME");
        form.getInputByName("_.password").setValueAttribute("PASSWD");
        j.submit(form);

        DescriptorImpl descriptor = (DescriptorImpl) j.jenkins.getDescriptorOrDie(BeakerBuilder.class);

        assertEquals("URL", descriptor.getBeakerURL());
        assertEquals("USERNAME", descriptor.getLogin());
        assertEquals("PASSWD", descriptor.getPassword().getPlainText());
    }

    @Test
    @LocalData
    @Issue("SECURITY-1545")
    public void migratePasswordToSecret() throws IOException {
        DescriptorImpl descriptor = (DescriptorImpl) j.jenkins.getDescriptorOrDie(BeakerBuilder.class);

        assertEquals("URL", descriptor.getBeakerURL());
        assertEquals("USERNAME", descriptor.getLogin());
        assertEquals("PASSWD", descriptor.getPassword().getPlainText());

        String text = descriptor.getConfigFile().asString();
        assertFalse(text, text.contains("PASSWD"));
    }
}
