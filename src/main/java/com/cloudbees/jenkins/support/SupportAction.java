/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
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

package com.cloudbees.jenkins.support;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

import com.cloudbees.jenkins.support.api.Component;
import com.cloudbees.jenkins.support.api.SupportProvider;
import com.cloudbees.jenkins.support.filter.ContentFilters;
import com.cloudbees.jenkins.support.impl.AboutBrowser;
import com.cloudbees.jenkins.support.impl.ReverseProxy;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Functions;
import hudson.Main;
import hudson.model.Api;
import hudson.model.Failure;
import hudson.model.RootAction;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jenkins.model.Jenkins;
import jenkins.util.ProgressiveRendering;
import jenkins.util.Timer;
import net.sf.json.JSON;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jvnet.localizer.Localizable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Main root action for generating support.
 */
@Extension
@ExportedBean
public class SupportAction implements RootAction, StaplerProxy {

    private static final Logger LOGGER = Logger.getLogger(SupportAction.class.getName());

    /**
     * @deprecated see {@link SupportPlugin#CREATE_BUNDLE}
     */
    @Deprecated
    public static final Permission CREATE_BUNDLE = SupportPlugin.CREATE_BUNDLE;
    /**
     * Our logger (retain an instance ref to avoid classloader leaks).
     */
    private final Logger logger = Logger.getLogger(SupportAction.class.getName());

    private static final String SUPPORT_BUNDLE_FILE_NAME = "support-bundle.zip";
    private static final String SUPPORT_BUNDLE_CREATION_FOLDER = Paths.get(System.getProperty("java.io.tmpdir"))
            .resolve("support-bundle")
            .toString();
    private static final Map<UUID, SupportBundleAsyncGenerator> generatorMap = new ConcurrentHashMap<>();

    @Override
    @Restricted(NoExternalUse.class)
    public Object getTarget() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    public String getIconFileName() {
        return "/plugin/support-core/images/support.svg";
    }

    public String getDisplayName() {
        return Messages.SupportAction_DisplayName();
    }

    public String getUrlName() {
        return "support";
    }

    public String getActionTitleText() {
        return getActionTitle().toString();
    }

    public Localizable getActionTitle() {
        SupportPlugin supportPlugin = SupportPlugin.getInstance();
        if (supportPlugin != null) {
            SupportProvider supportProvider = supportPlugin.getSupportProvider();
            if (supportProvider != null) {
                return supportProvider.getActionTitle();
            }
        }
        return Messages._SupportAction_DefaultActionTitle();
    }

    public Localizable getActionBlurb() {
        SupportPlugin supportPlugin = SupportPlugin.getInstance();
        if (supportPlugin != null) {
            SupportProvider supportProvider = supportPlugin.getSupportProvider();
            if (supportProvider != null) {
                return supportProvider.getActionBlurb();
            }
        }
        return Messages._SupportAction_DefaultActionBlurb();
    }

    @SuppressWarnings("unused") // used by Jelly
    @Exported
    @WebMethod(name = "components")
    public List<Component> getComponents() {
        return SupportPlugin.getComponents();
    }

    // for Jelly
    @Restricted(NoExternalUse.class)
    public Map<Component.ComponentCategory, List<Component>> getCategorizedComponents() {
        return Jenkins.get().getExtensionList(Component.class).stream()
                .filter(component -> component.isApplicable(Jenkins.class))
                .collect(Collectors.groupingBy(Component::getCategory, Collectors.toList()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Component.ComponentCategory::getLabel)))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .sorted(Comparator.comparing(Component::getDisplayName))
                                .collect(Collectors.toCollection(LinkedList::new)),
                        (e1, e2) -> e2,
                        LinkedHashMap::new));
    }

    public List<String> getBundles() {
        List<String> res = new ArrayList<>();
        File rootDirectory = SupportPlugin.getRootDirectory();
        File[] bundlesFiles = rootDirectory.listFiles((dir, name) -> name.endsWith(".zip"));
        if (bundlesFiles != null) {
            for (File bundleFile : bundlesFiles) {
                res.add(bundleFile.getName());
            }
        }
        Collections.sort(res);
        return res;
    }

    /**
     * Remote API access.
     */
    public final Api getApi() {
        return new Api(this);
    }

    public boolean isAnonymized() {
        return ContentFilters.get().isEnabled();
    }

    @RequirePOST
    public HttpResponse doDeleteBundles(StaplerRequest2 req) throws ServletException {
        JSONObject json = req.getSubmittedForm();
        if (!json.has("bundles")) {
            return HttpResponses.error(SC_BAD_REQUEST, "Missing bundles attribute");
        }
        Set<String> bundlesToDelete = getSelectedBundles(req, json);
        File rootDirectory = SupportPlugin.getRootDirectory();
        for (String bundleToDelete : bundlesToDelete) {
            File fileToDelete = new File(rootDirectory, bundleToDelete);
            logger.fine("Trying to delete bundle file " + fileToDelete.getAbsolutePath());
            try {
                if (fileToDelete.delete()) {
                    logger.info("Bundle " + fileToDelete.getAbsolutePath() + " successfully deleted.");
                } else {
                    logger.log(Level.SEVERE, "Unable to delete file " + fileToDelete.getAbsolutePath());
                }
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Unable to delete file " + fileToDelete.getAbsolutePath(), e);
            }
        }
        return HttpResponses.redirectToDot();
    }

    @RequirePOST
    public void doDownloadBundles(StaplerRequest2 req, StaplerResponse2 rsp) throws ServletException, IOException {
        JSONObject json = req.getSubmittedForm();
        if (!json.has("bundles")) {
            rsp.sendError(SC_BAD_REQUEST);
            return;
        }

        Set<String> bundlesToDownload = getSelectedBundles(req, json);
        File fileToDownload = null;
        if (bundlesToDownload.size() > 1) {
            // more than one bundles were selected, create a zip file
            fileToDownload = createZipFile(bundlesToDownload);
        } else if (bundlesToDownload.isEmpty()) {
            throw new Failure("No matching bundles");
        } else {
            fileToDownload = new File(
                    SupportPlugin.getRootDirectory(),
                    bundlesToDownload.iterator().next());
        }
        logger.fine("Trying to download file " + fileToDownload.getAbsolutePath());
        try {
            rsp.setContentType("application/zip");
            rsp.addHeader("Content-Disposition", "inline; filename=" + fileToDownload.getName() + ";");
            FileUtils.copyFile(fileToDownload, rsp.getOutputStream());
            logger.info("Bundle " + fileToDownload.getAbsolutePath() + " successfully downloaded");
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Unable to download file " + fileToDownload.getAbsolutePath(), e);
        } finally {
            if (bundlesToDownload.size() > 1) {
                if (fileToDownload.delete()) {
                    logger.log(Level.FINE, "Temporary multiBundle file deleted: " + fileToDownload.getAbsolutePath());
                } else {
                    logger.log(
                            Level.SEVERE,
                            "Unable to delete temporary multiBundle file: " + fileToDownload.getAbsolutePath());
                }
            }
        }
    }

    private Set<String> getSelectedBundles(StaplerRequest2 req, JSONObject json) {
        Set<String> bundles = new HashSet<>();
        List<String> existingBundles = getBundles();
        for (Selection s : req.bindJSONToList(Selection.class, json.get("bundles"))) {
            if (s.isSelected()) {
                if (existingBundles.contains(s.getName())) {
                    bundles.add(s.getName());
                } else {
                    logger.log(
                            Level.FINE,
                            "The bundle selected {0} does not exist, so it will not be processed",
                            s.getName());
                }
            }
        }
        return bundles;
    }

    private File createZipFile(Set<String> bundles) throws IOException {
        File rootDirectory = SupportPlugin.getRootDirectory();
        File zipFile = File.createTempFile(String.format("multiBundle(%s)-", bundles.size()), ".zip");
        zipFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(zipFile);
                ZipOutputStream zos = new ZipOutputStream(fos)) {
            byte[] buffer = new byte[1024];
            for (String bundle : bundles) {
                File file = new File(rootDirectory, bundle);
                try (FileInputStream fis = new FileInputStream(file)) {
                    zos.putNextEntry(new ZipEntry(file.getName()));
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error creating zip file: " + zipFile.getAbsolutePath(), e);
        }
        return zipFile;
    }

    /**
     * Generates a support bundle with selected components from the UI.
     * @param req The stapler request
     * @param rsp The stapler response
     * @throws ServletException If an error occurred during form submission
     * @throws IOException If an input or output exception occurs
     */
    @RequirePOST
    public HttpRedirect doDownload(StaplerRequest2 req, StaplerResponse2 rsp) throws ServletException, IOException {
        return doGenerateAllBundles(req, rsp);
    }

    /**
     * Generates a support bundle with selected components from the UI.
     * @param req The stapler request
     * @param rsp The stapler response
     * @throws ServletException If an error occurred during form submission
     * @throws IOException If an input or output exception occurs
     */
    @RequirePOST
    public HttpRedirect doGenerateAllBundles(StaplerRequest2 req, StaplerResponse2 rsp)
            throws ServletException, IOException {
        JSONObject json = req.getSubmittedForm();
        if (!json.has("components")) {
            rsp.sendError(SC_BAD_REQUEST);
            return HttpResponses.redirectTo("angry-jenkins");
        }
        logger.fine("Parsing request...");
        Set<String> remove = new HashSet<>();
        for (Selection s : req.bindJSONToList(Selection.class, json.get("components"))) {
            if (!s.isSelected()) {
                logger.log(Level.FINER, "Excluding ''{0}'' from list of components to include", s.getName());
                remove.add(s.getName());
                // JENKINS-63722: If "Master" or "Agents" are unselected, show a warning and add the new names for
                // those components to the list of unselected components for backward compatibility
                if ("Master".equals(s.getName()) || "Agents".equals(s.getName())) {
                    logger.log(
                            Level.WARNING,
                            Messages._SupportCommand_jenkins_63722_deprecated_ids(s.getName())
                                    .toString());
                    remove.add(s.getName() + "JVMProcessSystemMetricsContents");
                    remove.add(s.getName() + "SystemConfiguration");
                }
            }
        }
        logger.fine("Selecting components...");
        final List<Component> components = new ArrayList<>(getComponents());
        components.removeIf(c -> remove.contains(c.getId()) || !c.isEnabled());
        final SupportPlugin supportPlugin = SupportPlugin.getInstance();
        if (supportPlugin != null) {
            supportPlugin.setExcludedComponents(remove);
        }

        initializeComponentRequestContext(components);

        UUID taskId = UUID.randomUUID();
        SupportBundleAsyncGenerator supportBundleAsyncGenerator = new SupportBundleAsyncGenerator();
        supportBundleAsyncGenerator.init(taskId, components);
        generatorMap.put(taskId, supportBundleAsyncGenerator);

        return new HttpRedirect("progressPage?taskId=" + taskId);
    }

    /**
     * Generates a support bundle with only requested components.
     * @param components component names separated by comma.
     * @param rsp The stapler response
     * @throws IOException If an input or output exception occurs
     */
    @RequirePOST
    public HttpRedirect doGenerateBundle(@QueryParameter("components") String components, StaplerResponse2 rsp)
            throws IOException {
        if (components == null) {
            rsp.sendError(SC_BAD_REQUEST, "components parameter is mandatory");
            return HttpResponses.redirectTo("angry-jenkins");
        }
        Set<String> componentNames = Arrays.stream(components.split(",")).collect(Collectors.toSet());

        // JENKINS-63722: If "Master" or "Agents" are used, show a warning and add the new names for those components
        // to the selection for backward compatibility
        if (componentNames.contains("Master")) {
            logger.log(
                    Level.WARNING,
                    Messages._SupportCommand_jenkins_63722_deprecated_ids("Master")
                            .toString());
            componentNames.add("MasterJVMProcessSystemMetricsContents");
            componentNames.add("MasterSystemConfiguration");
        }
        if (componentNames.contains("Agents")) {
            logger.log(
                    Level.WARNING,
                    Messages._SupportCommand_jenkins_63722_deprecated_ids("Agents")
                            .toString());
            componentNames.add("AgentsJVMProcessSystemMetricsContents");
            componentNames.add("AgentsSystemConfiguration");
        }

        logger.fine("Selecting components...");
        List<Component> selectedComponents = getComponents().stream()
                .filter(c -> componentNames.contains(c.getId()))
                .collect(Collectors.toList());
        if (selectedComponents.isEmpty()) {
            rsp.sendError(SC_BAD_REQUEST, "selected component list is empty");
            return HttpResponses.redirectTo("angry-jenkins");
        }
        initializeComponentRequestContext(selectedComponents);

        UUID taskId = UUID.randomUUID();
        SupportBundleAsyncGenerator supportBundleAsyncGenerator = new SupportBundleAsyncGenerator();
        supportBundleAsyncGenerator.init(taskId, selectedComponents);
        generatorMap.put(taskId, supportBundleAsyncGenerator);

        return new HttpRedirect("progressPage?taskId=" + taskId);
    }

    /**
     * Initializes the request context for the given list of components.
     * This method sets request-specific information such as screen resolution and current request
     * to the components' context so that they can be used later during asynchronous processing
     * when the request is not available.
     */
    private static void initializeComponentRequestContext(List<Component> components) {
        for (Component component : components) {
            if (component instanceof AboutBrowser) {
                AboutBrowser aboutBrowser = (AboutBrowser) component;
                aboutBrowser.setScreenResolution(Functions.getScreenResolution());
                aboutBrowser.setCurrentRequest(Stapler.getCurrentRequest2());
            }

            if (component instanceof ReverseProxy) {
                ReverseProxy reverseProxy = (ReverseProxy) component;
                reverseProxy.setCurrentRequest(Stapler.getCurrentRequest2());
            }
        }
    }

    private void prepareBundle(StaplerResponse2 rsp, List<Component> components) throws IOException {
        logger.fine("Preparing response...");
        rsp.setContentType("application/zip");
        rsp.addHeader("Content-Disposition", "inline; filename=" + BundleFileName.generate() + ";");
        final ServletOutputStream servletOutputStream = rsp.getOutputStream();
        try {
            SupportPlugin.setRequesterAuthentication(Jenkins.getAuthentication2());
            try {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    SupportPlugin.writeBundle(servletOutputStream, components);
                } catch (IOException e) {
                    logger.log(Level.FINE, e.getMessage(), e);
                }
            } finally {
                SupportPlugin.clearRequesterAuthentication();
            }
        } finally {
            logger.fine("Response completed");
        }
    }

    public boolean selectedByDefault(Component c) {
        SupportPlugin supportPlugin = SupportPlugin.getInstance();
        return c.isSelectedByDefault()
                && (supportPlugin == null
                        || !supportPlugin.getExcludedComponents().contains(c.getId()));
    }

    public static class Selection {
        /** @see Component#getId */
        private final String name;

        private final boolean selected;

        @DataBoundConstructor
        public Selection(String name, boolean selected) {
            this.name = name;
            this.selected = selected;
        }

        public String getName() {
            return name;
        }

        public boolean isSelected() {
            return selected;
        }
    }

    public ProgressiveRendering getGenerateSupportBundle(@QueryParameter String taskId) throws Exception {
        ProgressiveRendering progressiveRendering = generatorMap.get(UUID.fromString(taskId));
        if (progressiveRendering == null) {
            throw new Failure("No task found for taskId: " + taskId);
        }

        if (Main.isUnitTest) {
            ((SupportBundleAsyncGenerator) progressiveRendering).startForTest();
        }

        return progressiveRendering;
    }

    public static class SupportBundleAsyncGenerator extends ProgressiveRendering {
        private final Logger logger = Logger.getLogger(SupportBundleAsyncGenerator.class.getName());
        private UUID taskId;
        private boolean isCompleted;
        private String pathToBundle;
        List<Component> components;

        public SupportBundleAsyncGenerator init(UUID taskId, List<Component> components) {
            this.taskId = taskId;
            this.components = components;
            return this;
        }

        public void startForTest() throws Exception {
            this.compute();
        }

        @Override
        protected void compute() throws Exception {
            File outputDir = new File(SUPPORT_BUNDLE_CREATION_FOLDER + "/" + taskId);
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create directory: " + outputDir.getAbsolutePath());
                }
            }

            logger.fine("Generating support bundle...");

            try (FileOutputStream fileOutputStream =
                    new FileOutputStream(new File(outputDir, SUPPORT_BUNDLE_FILE_NAME))) {
                SupportPlugin.setRequesterAuthentication(Jenkins.getAuthentication2());
                try {
                    try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                        SupportPlugin.writeBundle(fileOutputStream, components);
                    } catch (IOException e) {
                        logger.log(Level.FINE, e.getMessage(), e);
                    }
                } finally {
                    SupportPlugin.clearRequesterAuthentication();
                }
            } finally {
                logger.fine("Response completed");
            }

            progress(1);
            pathToBundle = outputDir.getAbsolutePath() + "/" + SUPPORT_BUNDLE_FILE_NAME;
            isCompleted = true;
        }

        @NonNull
        @Override
        protected JSON data() {
            JSONObject json = new JSONObject();
            json.put("isCompleted", isCompleted);
            json.put("pathToBundle", pathToBundle);
            json.put("status", 1);
            json.put("taskId", String.valueOf(taskId));
            return json;
        }
    }

    public void doDownloadBundle(@QueryParameter("taskId") String taskId, StaplerResponse2 rsp) throws IOException {
        File bundleFile = new File(SUPPORT_BUNDLE_CREATION_FOLDER + "/" + taskId + "/" + SUPPORT_BUNDLE_FILE_NAME);
        if (!bundleFile.exists()) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND, "Support bundle file not found");
            return;
        }

        rsp.setContentType("application/zip");
        rsp.addHeader("Content-Disposition", "attachment; filename=" + SUPPORT_BUNDLE_FILE_NAME);
        try (ServletOutputStream outputStream = rsp.getOutputStream();
                FileInputStream inputStream = new FileInputStream(bundleFile)) {
            IOUtils.copy(inputStream, outputStream);
        }

        // Clean up temporary files after assembling the full bundle
        Timer.get()
                .schedule(
                        () -> {
                            File outputDir = new File(SUPPORT_BUNDLE_CREATION_FOLDER + "/" + taskId);

                            try {
                                FileUtils.deleteDirectory(outputDir);
                                generatorMap.remove(taskId);
                                LOGGER.fine(() -> "Cleaned up temporary directory " + outputDir);

                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, () -> "Unable to delete " + outputDir);
                            }
                        },
                        1,
                        TimeUnit.HOURS);
    }
}
