/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gradle.test;

import org.elasticsearch.gradle.BuildPlugin;
import org.elasticsearch.gradle.BwcVersions;
import org.elasticsearch.gradle.DistributionDownloadPlugin;
import org.elasticsearch.gradle.ElasticsearchDistribution;
import org.elasticsearch.gradle.ElasticsearchDistribution.Flavor;
import org.elasticsearch.gradle.ElasticsearchDistribution.Platform;
import org.elasticsearch.gradle.ElasticsearchDistribution.Type;
import org.elasticsearch.gradle.Jdk;
import org.elasticsearch.gradle.JdkDownloadPlugin;
import org.elasticsearch.gradle.Version;
import org.elasticsearch.gradle.VersionProperties;
import org.elasticsearch.gradle.vagrant.BatsProgressLogger;
import org.elasticsearch.gradle.vagrant.VagrantBasePlugin;
import org.elasticsearch.gradle.vagrant.VagrantExtension;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskInputs;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.elasticsearch.gradle.vagrant.VagrantMachine.convertLinuxPath;
import static org.elasticsearch.gradle.vagrant.VagrantMachine.convertWindowsPath;

public class DistroTestPlugin implements Plugin<Project> {

    private static final String SYSTEM_JDK_VERSION = "11.0.2+9";
    private static final String GRADLE_JDK_VERSION = "12.0.1+12@69cfe15208a647278a19ef0990eea691";

    // all distributions used by distro tests. this is temporary until tests are per distribution
    private static final String PACKAGING_DISTRIBUTION = "packaging";
    private static final String COPY_DISTRIBUTIONS_TASK = "copyDistributions";
    private static final String IN_VM_SYSPROP = "tests.inVM";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(DistributionDownloadPlugin.class);
        project.getPluginManager().apply(BuildPlugin.class);

        // TODO: it would be useful to also have the SYSTEM_JAVA_HOME setup in the root project, so that running from GCP only needs
        // a java for gradle to run, and the tests are self sufficient and consistent with the java they use

        Version upgradeVersion = getUpgradeVersion(project);
        Provider<Directory> distributionsDir = project.getLayout().getBuildDirectory().dir("packaging/distributions");

        configureDistributions(project, upgradeVersion);
        TaskProvider<Copy> copyDistributionsTask = configureCopyDistributionsTask(project, upgradeVersion, distributionsDir);

        Map<String, TaskProvider<?>> distroTests = new HashMap<>();
        Map<String, TaskProvider<?>> batsTests = new HashMap<>();
        distroTests.put("distribution", configureDistroTest(project, distributionsDir, copyDistributionsTask));
        batsTests.put("bats oss", configureBatsTest(project, "oss", distributionsDir, copyDistributionsTask));
        batsTests.put("bats default", configureBatsTest(project, "default", distributionsDir, copyDistributionsTask));

        project.subprojects(vmProject -> {
            vmProject.getPluginManager().apply(VagrantBasePlugin.class);
            vmProject.getPluginManager().apply(JdkDownloadPlugin.class);
            List<Object> vmDependencies = new ArrayList<>(configureVM(vmProject));
            // a hack to ensure the parent task has already been run. this will not be necessary once tests are per distribution
            // which will eliminate the copy distributions task altogether
            vmDependencies.add(copyDistributionsTask);
            vmDependencies.add(project.getConfigurations().getByName("testRuntimeClasspath"));

            distroTests.forEach((desc, task) -> configureVMWrapperTask(vmProject, desc, task.getName(), vmDependencies));
            VagrantExtension vagrant = vmProject.getExtensions().getByType(VagrantExtension.class);
            batsTests.forEach((desc, task) -> {
                configureVMWrapperTask(vmProject, desc, task.getName(), vmDependencies).configure(t -> {
                    t.setProgressHandler(new BatsProgressLogger(project.getLogger()));
                    t.onlyIf(spec -> vagrant.isWindowsVM() == false); // bats doesn't run on windows
                });
            });
        });
    }

    private static Jdk createJdk(NamedDomainObjectContainer<Jdk> jdksContainer, String name, String version, String platform) {
        Jdk jdk = jdksContainer.create(name);
        jdk.setVersion(version);
        jdk.setPlatform(platform);
        return jdk;
    }

    private static Version getUpgradeVersion(Project project) {
        String upgradeFromVersionRaw = System.getProperty("tests.packaging.upgradeVersion");
        if (upgradeFromVersionRaw != null) {
            return Version.fromString(upgradeFromVersionRaw);
        }

        // was not passed in, so randomly choose one from bwc versions
        ExtraPropertiesExtension extraProperties = project.getExtensions().getByType(ExtraPropertiesExtension.class);

        if ((boolean) extraProperties.get("bwc_tests_enabled") == false) {
            // Upgrade tests will go from current to current when the BWC tests are disabled to skip real BWC tests
            return Version.fromString(project.getVersion().toString());
        }

        ExtraPropertiesExtension rootExtraProperties = project.getRootProject().getExtensions().getByType(ExtraPropertiesExtension.class);
        String firstPartOfSeed = rootExtraProperties.get("testSeed").toString().split(":")[0];
        final long seed = Long.parseUnsignedLong(firstPartOfSeed, 16);
        BwcVersions bwcVersions = (BwcVersions) extraProperties.get("bwcVersions");
        final List<Version> indexCompatVersions = bwcVersions.getIndexCompatible();
        return indexCompatVersions.get(new Random(seed).nextInt(indexCompatVersions.size()));
    }

    private static List<Object> configureVM(Project project) {
        String box = project.getName();

        // setup jdks used by the distro tests, and by gradle executing
        
        NamedDomainObjectContainer<Jdk> jdksContainer = JdkDownloadPlugin.getContainer(project);
        String platform = box.contains("windows") ? "windows" : "linux";
        Jdk systemJdk = createJdk(jdksContainer, "system", SYSTEM_JDK_VERSION, platform);
        Jdk gradleJdk = createJdk(jdksContainer, "gradle", GRADLE_JDK_VERSION, platform);

        // setup VM used by these tests
        VagrantExtension vagrant = project.getExtensions().getByType(VagrantExtension.class);
        vagrant.setBox(box);
        vagrant.vmEnv("SYSTEM_JAVA_HOME", convertPath(project, vagrant, systemJdk, "", ""));
        vagrant.vmEnv("PATH", convertPath(project, vagrant, gradleJdk, "/bin:$PATH", "\\bin;$Env:PATH"));
        vagrant.setIsWindowsVM(box.contains("windows"));

        return Arrays.asList(systemJdk, gradleJdk);
    }

    private static Object convertPath(Project project, VagrantExtension vagrant, Jdk jdk,
                                      String additionaLinux, String additionalWindows) {
        return new Object() {
            @Override
            public String toString() {
                if (vagrant.isWindowsVM()) {
                    return convertWindowsPath(project, jdk.getPath()) + additionalWindows;
                }
                return convertLinuxPath(project, jdk.getPath()) + additionaLinux;
            }
        };
    }

    private static TaskProvider<Copy> configureCopyDistributionsTask(Project project, Version upgradeVersion,
                                                                     Provider<Directory> archivesDir) {

        // temporary, until we have tasks per distribution
        return project.getTasks().register(COPY_DISTRIBUTIONS_TASK, Copy.class,
            t -> {
                t.into(archivesDir);
                t.from(project.getConfigurations().getByName(PACKAGING_DISTRIBUTION));

                Path archivesPath = archivesDir.get().getAsFile().toPath();

                // write bwc version, and append -SNAPSHOT if it is an unreleased version
                ExtraPropertiesExtension extraProperties = project.getExtensions().getByType(ExtraPropertiesExtension.class);
                BwcVersions bwcVersions = (BwcVersions) extraProperties.get("bwcVersions");
                final String upgradeFromVersion;
                if (bwcVersions.unreleasedInfo(upgradeVersion) != null) {
                    upgradeFromVersion = upgradeVersion.toString() + "-SNAPSHOT";
                } else {
                    upgradeFromVersion = upgradeVersion.toString();
                }
                TaskInputs inputs = t.getInputs();
                inputs.property("version", VersionProperties.getElasticsearch());
                inputs.property("upgrade_from_version", upgradeFromVersion);
                // TODO: this is serializable, need to think how to represent this as an input
                //inputs.property("bwc_versions", bwcVersions);
                t.doLast(action -> {
                    try {
                        Files.writeString(archivesPath.resolve("version"), VersionProperties.getElasticsearch());
                        Files.writeString(archivesPath.resolve("upgrade_from_version"), upgradeFromVersion);
                        // this is always true, but bats tests rely on it. It is just temporary until bats is removed.
                        Files.writeString(archivesPath.resolve("upgrade_is_oss"), "");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        });
    }

    private TaskProvider<GradleDistroTestTask> configureVMWrapperTask(Project project, String type, String destructiveTaskPath,
                                                                      List<Object> dependsOn) {
        int taskNameStart = destructiveTaskPath.lastIndexOf(':') + "destructive".length() + 1;
        String taskname = destructiveTaskPath.substring(taskNameStart);
        taskname = taskname.substring(0, 1).toLowerCase(Locale.ROOT) + taskname.substring(1);
        return project.getTasks().register(taskname, GradleDistroTestTask.class,
            t -> {
                t.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                t.setDescription("Runs " + type + " tests within vagrant");
                t.setTaskName(destructiveTaskPath);
                t.extraArg("-D'" + IN_VM_SYSPROP + "'");
                t.dependsOn(dependsOn);
            });
    }

    private TaskProvider<?> configureDistroTest(Project project, Provider<Directory> distributionsDir,
                                                   TaskProvider<Copy> copyPackagingArchives) {
        // TODO: don't run with security manager...
        return project.getTasks().register("destructiveDistroTest", Test.class,
            t -> {
                t.setMaxParallelForks(1);
                t.setWorkingDir(distributionsDir);
                if (System.getProperty(IN_VM_SYSPROP) == null) {
                    t.dependsOn(copyPackagingArchives);
                }
            });
    }

    private TaskProvider<?> configureBatsTest(Project project, String type, Provider<Directory> distributionsDir,
                                              TaskProvider<Copy> copyPackagingArchives) {
        return project.getTasks().register("destructiveBatsTest." + type, BatsTestTask.class,
            t -> {
                Directory batsDir = project.getLayout().getProjectDirectory().dir("bats");
                t.setTestsDir(batsDir.dir(type));
                t.setUtilsDir(batsDir.dir("utils"));
                t.setDistributionsDir(distributionsDir);
                t.setPackageName("elasticsearch" + (type.equals("oss") ? "-oss" : ""));
                if (System.getProperty(IN_VM_SYSPROP) == null) {
                    t.dependsOn(copyPackagingArchives);
                }
            });
    }
    
    private void configureDistributions(Project project, Version upgradeVersion) {
        NamedDomainObjectContainer<ElasticsearchDistribution> distributions = DistributionDownloadPlugin.getContainer(project);

        for (Type type : Arrays.asList(Type.DEB, Type.RPM)) {
            for (Flavor flavor : Flavor.values()) {
                for (boolean bundledJdk : Arrays.asList(true, false)) {
                    addDistro(distributions, type, null, flavor, bundledJdk, VersionProperties.getElasticsearch());
                }
            }
            // upgrade version is always bundled jdk
            // NOTE: this is mimicking the old VagrantTestPlugin upgrade behavior. It will eventually be replaced
            // witha dedicated upgrade test from every bwc version like other bwc tests
            addDistro(distributions, type, null, Flavor.DEFAULT, true, upgradeVersion.toString());
            if (upgradeVersion.onOrAfter("6.3.0")) {
                addDistro(distributions, type, null, Flavor.OSS, true, upgradeVersion.toString());
            }
        }
        for (Platform platform : Arrays.asList(Platform.LINUX, Platform.WINDOWS)) {
            for (Flavor flavor : Flavor.values()) {
                for (boolean bundledJdk : Arrays.asList(true, false)) {
                    addDistro(distributions, Type.ARCHIVE, platform, flavor, bundledJdk, VersionProperties.getElasticsearch());
                }
            }
        }

        // temporary until distro tests have one test per distro
        Configuration packagingConfig = project.getConfigurations().create(PACKAGING_DISTRIBUTION);
        List<Configuration> distroConfigs = distributions.stream().map(ElasticsearchDistribution::getConfiguration)
            .collect(Collectors.toList());
        packagingConfig.setExtendsFrom(distroConfigs);
    }

    private static void addDistro(NamedDomainObjectContainer<ElasticsearchDistribution> distributions,
                                  Type type, Platform platform, Flavor flavor, boolean bundledJdk, String version) {

        String name = flavor + "-" + (type == Type.ARCHIVE ? platform + "-" : "") + type + (bundledJdk ? "" : "-no-jdk") + "-" + version;
        if (distributions.findByName(name) != null) {
            return;
        }
        distributions.create(name, d -> {
            d.setFlavor(flavor);
            d.setType(type);
            if (type == Type.ARCHIVE) {
                d.setPlatform(platform);
            }
            d.setBundledJdk(bundledJdk);
            d.setVersion(version);
        });
    }
}
