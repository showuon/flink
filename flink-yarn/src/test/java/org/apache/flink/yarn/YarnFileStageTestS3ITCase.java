/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.yarn;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.fs.hdfs.HadoopFileSystem;
import org.apache.flink.testutils.junit.RetryOnException;
import org.apache.flink.testutils.junit.extensions.retry.RetryExtension;
import org.apache.flink.testutils.s3.S3TestCredentials;

import org.apache.hadoop.util.VersionUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.Assumptions.assumeThatThrownBy;

/**
 * Tests for verifying file staging during submission to YARN works with the S3A file system.
 *
 * <p>Note that the setup is similar to
 * <tt>org.apache.flink.fs.s3hadoop.HadoopS3FileSystemITCase</tt>.
 */
@ExtendWith(RetryExtension.class)
class YarnFileStageTestS3ITCase {

    private static final String TEST_DATA_DIR = "tests-" + UUID.randomUUID();

    @BeforeAll
    static void checkCredentialsAndSetup(@TempDir File tempFolder) throws IOException {
        // check whether credentials exist
        S3TestCredentials.assumeCredentialsAvailable();

        setupCustomHadoopConfig(tempFolder);
    }

    @AfterAll
    static void resetFileSystemConfiguration() {
        FileSystem.initialize(new Configuration());
    }

    /**
     * Create a Hadoop config file containing S3 access credentials.
     *
     * <p>Note that we cannot use them as part of the URL since this may fail if the credentials
     * contain a "/" (see <a
     * href="https://issues.apache.org/jira/browse/HADOOP-3733">HADOOP-3733</a>).
     */
    private static void setupCustomHadoopConfig(File tempFolder) throws IOException {
        File hadoopConfig =
                Files.createTempFile(tempFolder.toPath(), UUID.randomUUID().toString(), "")
                        .toFile();
        Map<String /* key */, String /* value */> parameters = new HashMap<>();

        // set all different S3 fs implementation variants' configuration keys
        parameters.put("fs.s3a.access.key", S3TestCredentials.getS3AccessKey());
        parameters.put("fs.s3a.secret.key", S3TestCredentials.getS3SecretKey());

        parameters.put("fs.s3.awsAccessKeyId", S3TestCredentials.getS3AccessKey());
        parameters.put("fs.s3.awsSecretAccessKey", S3TestCredentials.getS3SecretKey());

        parameters.put("fs.s3n.awsAccessKeyId", S3TestCredentials.getS3AccessKey());
        parameters.put("fs.s3n.awsSecretAccessKey", S3TestCredentials.getS3SecretKey());

        try (PrintStream out = new PrintStream(new FileOutputStream(hadoopConfig))) {
            out.println("<?xml version=\"1.0\"?>");
            out.println("<?xml-stylesheet type=\"text/xsl\" href=\"configuration.xsl\"?>");
            out.println("<configuration>");
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                out.println("\t<property>");
                out.println("\t\t<name>" + entry.getKey() + "</name>");
                out.println("\t\t<value>" + entry.getValue() + "</value>");
                out.println("\t</property>");
            }
            out.println("</configuration>");
        }

        final Configuration conf = new Configuration();
        conf.set(CoreOptions.ALLOWED_FALLBACK_FILESYSTEMS, "s3;s3a;s3n");

        FileSystem.initialize(conf, null);
    }

    /**
     * Verifies that nested directories are properly copied with to the given S3 path (using the
     * appropriate file system) during resource uploads for YARN.
     *
     * @param scheme file system scheme
     * @param pathSuffix test path suffix which will be the test's target path
     */
    private void testRecursiveUploadForYarn(String scheme, String pathSuffix, File tempFolder)
            throws Exception {

        final Path basePath =
                new Path(S3TestCredentials.getTestBucketUriWithScheme(scheme) + TEST_DATA_DIR);
        final HadoopFileSystem fs = (HadoopFileSystem) basePath.getFileSystem();

        assumeThat(fs.exists(basePath)).isTrue();

        try {
            final Path directory = new Path(basePath, pathSuffix);

            YarnFileStageTest.testRegisterMultipleLocalResources(
                    fs.getHadoopFileSystem(),
                    new org.apache.hadoop.fs.Path(directory.toUri()),
                    Path.CUR_DIR,
                    tempFolder,
                    false,
                    false);
        } finally {
            // clean up
            fs.delete(basePath, true);
        }
    }

    @TestTemplate
    @RetryOnException(times = 3, exception = Exception.class)
    void testRecursiveUploadForYarnS3n(@TempDir File tempFolder) throws Exception {
        // skip test on Hadoop 3: https://issues.apache.org/jira/browse/HADOOP-14738
        assumeThat(VersionUtil.compareVersions(System.getProperty("hadoop.version"), "3.0.0") < 0)
                .as("This test is skipped for Hadoop versions above 3")
                .isTrue();

        assumeThatThrownBy(() -> Class.forName("org.apache.hadoop.fs.s3native.NativeS3FileSystem"))
                .as("Skipping test because NativeS3FileSystem is not in the class path")
                .isNull();
        testRecursiveUploadForYarn("s3n", "testYarn-s3n", tempFolder);
    }

    @TestTemplate
    @RetryOnException(times = 3, exception = Exception.class)
    public void testRecursiveUploadForYarnS3a(@TempDir File tempFolder) throws Exception {
        assumeThatThrownBy(() -> Class.forName("org.apache.hadoop.fs.s3a.S3AFileSystem"))
                .as("Skipping test because S3AFileSystem is not in the class path")
                .isNull();
        testRecursiveUploadForYarn("s3a", "testYarn-s3a", tempFolder);
    }
}
