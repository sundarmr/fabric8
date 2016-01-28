/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.agent.download.impl;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;

import io.fabric8.maven.MavenResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenDownloadTask extends AbstractRetryableDownloadTask {

    public static Logger LOG = LoggerFactory.getLogger(MavenDownloadTask.class);

    private final MavenResolver resolver;

    public MavenDownloadTask(ScheduledExecutorService executor, MavenResolver resolver, String url) {
        super(executor, url);
        this.resolver = resolver;
    }

    protected File download() throws Exception {
        LOG.info("GG: download(" + url + ")");
        File download = resolver.download(url);
        LOG.info("GG: download(" + url + "): " + download);
        return download;
    }

}
