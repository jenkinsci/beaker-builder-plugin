package org.jenkinsci.plugins.beakerbuilder;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;
import java.util.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;

import net.sf.json.JSONObject;

import org.apache.xmlrpc.XmlRpcException;
import org.jenkinsci.plugins.beakerbuilder.utils.ConsoleLogger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.github.vjuranek.beaker4j.beaker.BeakerServer;
import com.github.vjuranek.beaker4j.client.BeakerClient;
import com.github.vjuranek.beaker4j.remote_model.BeakerJob;
import com.github.vjuranek.beaker4j.remote_model.Identity;
import com.github.vjuranek.beaker4j.remote_model.TaskResult;
import com.github.vjuranek.beaker4j.remote_model.TaskStatus;

/**
 * Builder which provides possibility to schedule external Beaker job, waits for its completion and sets up job result
 * according to Beaker job status.
 *
 * @author vjuranek
 *
 */
public class BeakerBuilder extends Builder {

    /**
     * Beaker job XML
     */
    private final JobSource jobSource;
    private final boolean downloadFiles;

    @DataBoundConstructor
    public BeakerBuilder(JobSource jobSource, boolean downloadFiles) {
        this.jobSource = jobSource;
        this.downloadFiles = downloadFiles;
        LOGGER.fine("download is " + downloadFiles);
    }

    public JobSource getJobSource() {
        return jobSource;
    }

    public boolean getDownloadFiles() {
        return downloadFiles;
    }

    /**
     * Prepares job XML, schedule the job in Beaker, waits for the result and
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException {
        ConsoleLogger console = new ConsoleLogger(listener);

        String jobXml;
        try {
            jobXml = jobSource.getJobContent(build, listener);
        } catch (IOException ex) {
            log(console, "[Beaker] ERROR: Cannot create job source file, cause " + ex.getMessage());
            return false;
        }

        BeakerJob job = scheduleJob(jobXml, build, console);
        if (job == null) {
            log(console, "[Beaker] ERROR: Something went wrong when submitting job to Beaker, got NULL from Beaker, see console and Jenkins log for details");
            return false;
        }
        //TODO cancel job in Beaker if running before leaving

        // job exists in Beaker, we can create an action pointing to it
        BeakerBuildAction bba = new BeakerBuildAction(job.getJobNumber(), getDescriptor().getBeakerURL());
        build.addAction(bba);
        log(console, "[Beaker] INFO: Job successfully submitted to Beaker, job ID is " + job.getJobId());

        // wait for job completion
        if (!waitForJobCompletion(job, console))
            return false;

        // set build result according to Beaker result
        setBuildResult(job, build, console);

        //try to download job files into workspace
        if(downloadFiles)
            downloadJobFiles(job, build, console);

        return true;
    }

    /**
     * Schedules job on Beaker server. If scheduling is successful, add {@link BeakerBuildAction} to the job.
     *
     * @param build
     * @return True if job scheduling is successful.
     */
    private @CheckForNull BeakerJob scheduleJob(String jobXml, AbstractBuild<?, ?> build, ConsoleLogger console) {
        LOGGER.fine("Job XML is: \n" + jobXml);
        BeakerJob job = null;
        try {
            final BeakerClient client = getDescriptor().getBeakerClient();
            final Identity identity = getDescriptor().getIdentity();

            if (client == null || identity == null) {
                log(console, "[Beaker] ERROR: Beaker connection not configured properly");
                return null;
            }

            //client credentials can expire after some time, renew them before scheduling of each build
            client.authenticate(identity);
            job = client.scheduleJob(jobXml);
        } catch(XmlRpcException e) {
            log(console, "[Beaker] ERROR: Job scheduling has failed, reason: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Beaker job scheduling has failed", e);
        }
        return job;
    }

    /**
     * Starts {@link TaskWatchdog} and waits until job finishes. If job status has changes, sends notification to the
     * console log.
     *
     * @return True if waiting for job finishes normally.
     */
    /*package*/ static boolean waitForJobCompletion(BeakerJob job, ConsoleLogger console) {
        TaskWatchdog watchdog = new TaskWatchdog(job.getBeakerTask(), TaskStatus.NEW);
        Timer timer = new Timer("Waiting for beaker job " + job.getJobId());
        timer.scheduleAtFixedRate(watchdog, TaskWatchdog.DEFAULT_DELAY, TaskWatchdog.DEFAULT_PERIOD);
        synchronized (watchdog) {
            while (!watchdog.isFinished()) {
                try {
                    watchdog.wait(); // TODO timeout
                } catch (InterruptedException e) {
                    timer.cancel();
                    log(console, "[Beaker] INFO: Job aborted");
                    return false;
                }
                log(console, "[Beaker] INFO: Job has changed state from " + watchdog.getOldStatus() + " state to state "
                        + watchdog.getStatus());
            }
        }
        timer.cancel();

        log(console, "[Beaker] INFO: Job finished");
        return true;
    }

    /**
     * Sets the build result according to Beaker job result.
     *
     * @param build
     */
    private void setBuildResult(BeakerJob job, AbstractBuild<?, ?> build, ConsoleLogger console) {
        TaskResult result = null;
        try {
            result = job.getBeakerTask().getInfo().getResult();
        } catch (XmlRpcException e) {
            LOGGER.log(Level.INFO, "Beaker error: cannot get result from Beaker ", e);
            log(console, "[Beaker] ERROR: Cannot get job result from Beaker, check Jenkins logs for more details");
        }

        if (result == null) {
            log(console, "[Beaker] ERROR: Cannot get job result from Beaker, got NULL");
            build.setResult(Result.FAILURE);
        }

        // Beaker <---> Jenkins result mapping
        // TODO check, if this is correct
        // TODO add ABORTED result
        // TODO add CANCELED result
        switch (result) {
        case PANIC:
        case FAIL:
            build.setResult(Result.FAILURE);
            break;
        case WARN:
            build.setResult(Result.UNSTABLE);
            break;
        case PASS:
            build.setResult(Result.SUCCESS);
            break;
        default:
            build.setResult(Result.UNSTABLE);
            log(console, "[Beaker] INFO: Unknow job result, setting build result to UNSTABLE");
            break;
        }

    }

    private void downloadJobFiles(BeakerJob job, AbstractBuild<?, ?> build, ConsoleLogger console) {
        FilePath beakerFileDir = new FilePath(build.getWorkspace(),"beaker/" + job.getJobId().replaceAll(":", "_"));
        try {
            beakerFileDir.mkdirs();
        } catch(IOException e) {
            LOGGER.log(Level.INFO, "Beaker error: cannot create dir for Beaker files", e);
            log(console, "[Beaker] ERROR: Cannot create dir for Beaker files: " + e.getMessage());
        } catch(InterruptedException e) {
            LOGGER.log(Level.INFO, "Beaker error: creating dir for Beaker files interrupted", e);
            log(console, "[Beaker] ERROR: Creating dir for Beaker files interrupted: " + e.getMessage());
        }

        log(console, "[Beaker] INFO: Trying to download job file into " + beakerFileDir.getRemote());
        try {
            ArrayList<Map<String, String>> files = job.getFiles();
            for(Map<String, String> f : files) {
               FilePath fp = new FilePath(beakerFileDir, f.get("filename"));
               log(console, "[Beaker] INFO: Downloading " + f.get("filename") + " into " + fp.getRemote());
               try {
                   fp.copyFrom(new URL(f.get("url")));
               } catch(IOException e) {
                   log(console, "[Beaker] ERROR: Something went wrong when downloading " + f.get("filename") + ", check Jenkins log for details.");
                   LOGGER.log(Level.INFO, "Beaker error: cannot donwload file " + f.get("filename"), e);
               } catch(InterruptedException e) {
                   log(console, "[Beaker] ERROR: Something went wrong when downloading " + f.get("filename") + ", check Jenkins log for details.");
                   LOGGER.log(Level.INFO, "Beaker error: cannot donwload file " + f.get("filename"), e);
               }
            }
        } catch(XmlRpcException e) {
            LOGGER.log(Level.INFO, "Beaker error: cannot download files from Beaker ", e);
            log(console, "[Beaker] ERROR: Cannot download job files from Beaker: " + e.getMessage());
        }
    }

    /**
     * Logs messages into Jenkins console.
     *
     * @param message
     *            Message to be logges
     */
    private static void log(ConsoleLogger console, String message) {
        console.logAnnot(message);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        /**
         * Beaker server URL
         */
        private String beakerURL;

        // TODO provide a way to store the credential in Jenkins authentication center.
        /**
         * Beaker login
         */
        private String login;

        /**
         * Beaker password
         */
        private String password;

        private transient BeakerClient beakerClient;
        private transient Identity identity;

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(@SuppressWarnings("rawtypes")Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Execute Beaker task";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            if (beakerURL.endsWith("/"))
                beakerURL = beakerURL.substring(0, beakerURL.length() - 1);
            save();

            beakerClient = null;
            identity = null;

            return super.configure(req, formData);
        }

        @Restricted(DoNotUse.class) // Databinding
        public void setBeakerURL(String beakerURL) {
            this.beakerURL = beakerURL;
        }

        @Restricted(DoNotUse.class) // Databinding
        public void setLogin(String login) {
            this.login = login;
        }

        @Restricted(DoNotUse.class) // Databinding
        public void setPassword(String password) {
            this.password = password;
        }

        /**
         * Establish Beaker connection if there is none until now.
         */
        private void setupClient() {
            if (beakerClient != null) return;
            if (beakerURL != null && !"".equals(beakerURL.trim())) {
                beakerClient = BeakerServer.getXmlRpcClient(beakerURL);

                try {
                    identity = beakerClient.authenticate(new Identity(login, password));
                } catch (XmlRpcException e) {
                    beakerClient = null;
                    LOGGER.log(Level.WARNING, "Unable to create beaker client", e);
                }
            }
        }

        /**
         * Tries to connect to Beaker server and verify that provided credential works.
         */
        @Restricted(DoNotUse.class)
        public FormValidation doTestConnection(
                @QueryParameter("beakerURL") final String beakerURL,
                @QueryParameter("login") final String login,
                @QueryParameter("password") final String password
        ) {
            LOGGER.fine("Trying to get client for " + beakerURL);
            BeakerClient bc = BeakerServer.getXmlRpcClient(beakerURL);
            try {
                Identity ident = bc.authenticate(new Identity(login, password));

                return FormValidation.ok("Connected as " + ident.whoAmI());
            } catch (Exception e) {
                return FormValidation.error(e, "Cannot connect to " + beakerURL);
            }
        }

        public String getBeakerURL() {
            return beakerURL;
        }

        public String getLogin() {
            return login;
        }

        public String getPassword() {
            return password;
        }

        /**
         * @return Can be null if not configured properly.
         */
        public @CheckForNull BeakerClient getBeakerClient() {
            setupClient();
            return beakerClient;
        }

        /**
         * Beaker identity.
         *
         * Client is expected to {@link Identity#authenticate()} as previous
         * authentication authentication of the same identity can be expired.
         *
         * @return Can be null if not configured properly.
         */
        public @CheckForNull Identity getIdentity() {
            setupClient();
            return identity;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(BeakerBuilder.class.getName());
}
