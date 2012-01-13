package hudson.plugins.qmake;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import hudson.FilePath;
public
class QmakeBuilderImpl {

	private static final String QMAKE_DEFAULT = "qmake";

	String qmakeBin;
	boolean isWindows;

	public QmakeBuilderImpl() {
		super();
	}

	void setQmakeBin(Map<String, String> envVars,
			String globalQmakeBin, boolean isWindows) throws IOException, InterruptedException {
		qmakeBin = QMAKE_DEFAULT;

		if (globalQmakeBin != null && globalQmakeBin.length() > 0) {
		    File fileInfo = new File( globalQmakeBin );
		    if (fileInfo.exists())
            qmakeBin = fileInfo.toString();
		}

		if (envVars.containsKey("QTDIR")) {
			String checkName = envVars.get("QTDIR");
			if (isWindows)
				checkName += "\\bin\\qmake.exe";
			else
				checkName += "/bin/qmake";

			File fileInfo = new File(checkName);
			if (fileInfo.exists())
				qmakeBin = fileInfo.toString();
		}
	}

	String buildQMakeCall(FilePath projectFile, String extraArguments) {
		String qmakeCall = qmakeBin + " -r \"" + projectFile + "\"";

		if (!extraArguments.isEmpty()) {
			qmakeCall += " " + extraArguments;
		}
		return qmakeCall;
	}
}
