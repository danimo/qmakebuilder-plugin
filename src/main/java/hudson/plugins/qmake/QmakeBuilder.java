package hudson.plugins.qmake;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.File;
import java.io.PrintStream;
import java.io.IOException;
import java.util.List;

/**
 * Executes <tt>qmake</tt> and <tt>make</tt> as the build process for a Qt-based build.
 *
 * @author Daniel Molkentin
 * @author Tyler Mace
 */
public class QmakeBuilder extends Builder {

	private String projectFile;
	private String extraArguments;
	private String extraTargets;
	private String shadowBuildDir;
	private boolean useShadowBuild;

	private QmakeBuilderImpl builderImpl;

	@DataBoundConstructor
	public QmakeBuilder(String projectFile, String extraArguments, String extraTargets, String shadowBuildDir, boolean useShadowBuild) {
		this.projectFile = projectFile;
		this.extraArguments = extraArguments;
		this.extraTargets = extraTargets;
		this.shadowBuildDir = shadowBuildDir;
		this.useShadowBuild = useShadowBuild;
	}

	public String getProjectFile() {
		return this.projectFile;
	}

	public String getExtraArguments() {
		return this.extraArguments;
	}

	public String getExtraTargets() {
		return this.extraTargets;
	}

	public String getShadowBuildDir() {
		return this.shadowBuildDir;
	}

	public boolean getUseShadowBuild() {
		return this.useShadowBuild;
    }

	@Override
	public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		PrintStream logger = listener.getLogger();

		logger.println("MODULE: " + build.getModuleRoot());

		final boolean isWindows = !launcher.isUnix();

		if (this.builderImpl == null) {
			this.builderImpl = new QmakeBuilderImpl();
		}
		EnvVars envVars = build.getEnvironment(listener);

		String theProjectFile = EnvVarReplacer.replace(this.projectFile, envVars).trim();

		try {
			this.builderImpl.setQmakeBin(envVars, getDescriptor().getQmakePath(), isWindows);
		} catch (Exception e) {
			logger.println("Exception while processing qmake path: " + getDescriptor().getQmakePath());
			logger.println(e.getMessage());
			return false;
		}

		FilePath projectFilePath = new FilePath(build.getWorkspace(), theProjectFile);
		FilePath sourceDir = projectFilePath.getParent();
		FilePath buildDir;

		try {
			String theShadowBuildDir = this.shadowBuildDir;
			if (theShadowBuildDir.isEmpty())
				theShadowBuildDir = "";
			buildDir = new FilePath(sourceDir, EnvVarReplacer.replace(theShadowBuildDir, envVars));
			if (this.useShadowBuild) {
				logger.println("Using shadow build: " + buildDir);
				buildDir.mkdirs();
			}
		} catch(IOException ioe) {
			logger.println("IO Exception with build directory: " + projectFile);
			logger.println(ioe.getMessage());
			return false;
		}

		String qmakeCall = builderImpl.buildQMakeCall(projectFilePath, EnvVarReplacer.replace(this.extraArguments, envVars));
		logger.println("QMake call : " + qmakeCall);

		try {
			int result = launcher.launch().cmds(Util.tokenize(qmakeCall)).envs(envVars).stdout(logger).pwd(buildDir).join();
			if (result != 0)
				return false;

			String makeExe = getDescriptor().getMakeCmdUnix();
			if (!launcher.isUnix())
          makeExe = getDescriptor().getMakeCmdWindows();

			makeExe = EnvVarReplacer.replace(makeExe, envVars);

			result = launcher.launch().cmds(Util.tokenize(makeExe)).envs(envVars).stdout(logger).pwd(buildDir).join();
			if (result != 0)
				return false;

			// TODO: is tokenize the right choice? (mostly using it for chomp here)
			String[] targets = Util.tokenize(this.extraTargets);
			for (String target : targets) {
				String exec = makeExe + " " + target;
				result = launcher.launch().cmds(Util.tokenize(exec)).envs(envVars).stdout(logger).pwd(buildDir).join();
				if (result != 0)
					return false;
			}
			return true;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
		return false;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl)super.getDescriptor();
	}

	/**
	 * Descriptor for {@link QmakeBuilder}. Used as a singleton.
	 * The class is marked as public so that it can be accessed from views.
	 *
	 * <p>
	 * See <tt>resources/hudson/plugins/qmake/QmakeBuilder/*.jelly</tt>
	 * for the actual HTML fragment for the configuration screen.
	 */
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
		/**
		 * To persist global configuration information,
		 * simply store it in a field and call save().
		 *
		 * <p>
		 * If you don't want fields to be persisted, use <tt>transient</tt>.
		 */
		private String qmakePath;
		private String makeCmdUnix;
		private String makeCmdWindows;

		public DescriptorImpl() {
			super(QmakeBuilder.class);
			load();
		}

		/**
		 * Performs on-the-fly validation of the form field 'projectFile'.
		 *
		 * @param value
		 */
		public FormValidation doCheckProjectFile(@QueryParameter final String value) throws IOException, ServletException {
			if(value.length()==0)
				return FormValidation.error("Please set a project file");
			if(value.length() < 1)
				return FormValidation.warning("Isn't the name too short?");

			File file = new File(value);
			if (file.isDirectory())
				return FormValidation.error("Project file is a directory");

			if (file.getName().endsWith(".pri"))
				return FormValidation.error("Project includes cannot be used to build a project");

			if (!file.getName().endsWith(".pro"))
				return FormValidation.warning("Project file is not have .pro extension");

			return FormValidation.ok();
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "QMake Build";
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
			// to persist global configuration information,
			// set that to properties and call save().
			qmakePath = o.getString("qmakePath");
			makeCmdUnix = o.getString("makeCmdUnix");
			makeCmdWindows = o.getString("makeCmdWindows");
			save();
			return super.configure(req, o);
		}

		public String getQmakePath() {
			return this.qmakePath;
		}

		public String getMakeCmdUnix() {
			return this.makeCmdUnix;
		}

		public String getMakeCmdWindows() {
			return this.makeCmdWindows;
		}

	}
}

