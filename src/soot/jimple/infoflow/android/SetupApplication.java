package soot.jimple.infoflow.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import soot.Main;
import soot.PackManager;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.jimple.infoflow.InfoflowResults;
import soot.jimple.infoflow.android.AndroidSourceSinkManager.LayoutMatchingMode;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.android.data.parsers.PermissionMethodParser;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.android.resources.ARSCFileParser;
import soot.jimple.infoflow.android.resources.ARSCFileParser.AbstractResource;
import soot.jimple.infoflow.android.resources.ARSCFileParser.StringResource;
import soot.jimple.infoflow.android.resources.LayoutControl;
import soot.jimple.infoflow.android.resources.LayoutFileParser;
import soot.jimple.infoflow.entryPointCreators.AndroidEntryPointCreator;
import soot.jimple.infoflow.taintWrappers.EasyTaintWrapper;
import soot.jimple.infoflow.util.SootMethodRepresentationParser;
import soot.options.Options;

public class SetupApplication {

	private List<AndroidMethod> sinks = new ArrayList<AndroidMethod>();
	private List<AndroidMethod> sources = new ArrayList<AndroidMethod>();
	private List<AndroidMethod> callbackMethods = new ArrayList<AndroidMethod>();
	
	private Set<String> entrypoints = null;
	
	private Map<Integer, LayoutControl> layoutControls;
	private List<ARSCFileParser.ResPackage> resourcePackages = null;
	private String appPackageName = "";
	
	private String androidJar;
	private String apkFileLocation;
	private String taintWrapperFile;

	public SetupApplication(){
		
	}
	
	public SetupApplication(String androidJar, String apkFileLocation) {
		this.androidJar = androidJar;
		this.apkFileLocation = apkFileLocation;
	}

	public void printSinks(){
		System.out.println("Sinks:");
		for (AndroidMethod am : sinks) {
			System.out.println(am.toString());
		}
		System.out.println("End of Sinks");
	}

	
	public void printSources(){
		System.out.println("Sources:");
		for (AndroidMethod am : sources) {
			System.out.println(am.toString());
		}
		System.out.println("End of Sources");
	}

	public void printEntrypoints(){
		if (this.entrypoints == null)
			System.out.println("Entry points not initialized");
		else {
			System.out.println("Classes containing entry points:");
			for (String className : entrypoints)
				System.out.println("\t" + className);
			System.out.println("End of Entrypoints");
		}
	}

	public void setAndroidJar(String androidJar) {
		this.androidJar = androidJar;
	}

	public void setApkFileLocation(String apkFileLocation) {
		this.apkFileLocation = apkFileLocation;
	}
	
	public void setTaintWrapperFile(String taintWrapperFile) {
		this.taintWrapperFile = taintWrapperFile;
	}

	public void calculateSourcesSinksEntrypoints
			(String entryPointsFile,
			String sourceSinkFile) throws IOException {
		ProcessManifest processMan = new ProcessManifest();

		// To look for callbacks, we need to start somewhere. We use the Android
		// lifecycle methods for this purpose.
		processMan.loadManifestFile(apkFileLocation);
		this.appPackageName = processMan.getPackageName();
		this.entrypoints = processMan.getEntryPointClasses();

		// Parse the resource file
		ARSCFileParser resParser = new ARSCFileParser();
		resParser.parse(apkFileLocation);
		this.resourcePackages = resParser.getPackages();
		
		soot.G.reset();

		// Collect the callback interfaces implemented in the app's source code
		AnalyzeJimpleClass jimpleClass = new AnalyzeJimpleClass();
		jimpleClass.collectCallbackMethods();
		
		// Find the user-defined sources in the layout XML files
		LayoutFileParser lfp = new LayoutFileParser(this.appPackageName);
		lfp.parseLayoutFile(apkFileLocation, entrypoints);
		
		// Run the soot-based operations
		runSootBasedPhases();

		// Collect the results of the soot-based phases
		for (AndroidMethod am : jimpleClass.getCallbackMethods())
			this.callbackMethods.add(am);
		this.layoutControls = lfp.getUserControls();
		
		// Collect the XML-based callback methods
		for (Entry<SootClass, Set<Integer>> lcentry : jimpleClass.getLayoutClasses().entrySet())
			for (Integer classId : lcentry.getValue()) {
				AbstractResource resource = resParser.findResource(classId);
				if (resource instanceof StringResource) {
					StringResource strRes = (StringResource) resource;
					if (lfp.getCallbackMethods().containsKey(strRes.getValue()))
						for (String methodName : lfp.getCallbackMethods().get(strRes.getValue()))
							this.callbackMethods.add(new AndroidMethod(lcentry.getKey().getMethodByName(methodName)));
				}
				else
					System.err.println("Unexpected resource type for layout class");
			}
		System.out.println("Found " + this.callbackMethods.size() + " callback methods");

		PermissionMethodParser parser = PermissionMethodParser.fromFile(sourceSinkFile);
		for (AndroidMethod am : parser.parse()){
			if (am.isSource())
				sources.add(am);
			if(am.isSink())
				sinks.add(am);
		}
		
		//add sink for Intents:
		AndroidMethod setResult = new AndroidMethod(SootMethodRepresentationParser.v().parseSootMethodString
				("<android.app.Activity: void startActivity(android.content.Intent)>"));
		setResult.setSink(true);
		sinks.add(setResult);
	}

	/**
	 * Runs Soot and executes all analysis phases that have been registered so
	 * far.
	 */
	private void runSootBasedPhases() {
		Options.v().set_no_bodies_for_excluded(true);
		Options.v().set_allow_phantom_refs(true);
		Options.v().set_output_format(Options.output_format_none);
		Options.v().set_whole_program(true);
		Options.v().set_soot_classpath(apkFileLocation + File.pathSeparator
				+ Scene.v().getAndroidJarPath(androidJar, apkFileLocation));
		Options.v().set_android_jars(androidJar);
		Options.v().set_src_prec(Options.src_prec_apk);
		Options.v().set_process_dir(Arrays.asList(this.entrypoints.toArray()));
		Options.v().set_app(true);
		Main.v().autoSetOptions();

		Scene.v().loadNecessaryClasses();
		
		for (String className : this.entrypoints) {
			SootClass c = Scene.v().forceResolve(className, SootClass.BODIES);
			c.setApplicationClass();	
		}

		AndroidEntryPointCreator entryPointCreator = new AndroidEntryPointCreator
			(new ArrayList<String>(this.entrypoints));
		SootMethod entryPoint = entryPointCreator.createDummyMain();
		
		Scene.v().setEntryPoints(Collections.singletonList(entryPoint));
		PackManager.v().runPacks();
	}

	/**
	 * Runs the data flow analysis
	 * @return The results of the data flow analysis
	 */
	public InfoflowResults runInfoflow(){
		System.out.println("Running data flow analysis on " + apkFileLocation + " with "
				+ sources.size() + " sources and " + sinks.size() + " sinks...");
		soot.jimple.infoflow.Infoflow info = new soot.jimple.infoflow.Infoflow(androidJar, false);
		String path = apkFileLocation + File.pathSeparator + Scene.v().getAndroidJarPath(androidJar, apkFileLocation);
		
		try {
			if (this.taintWrapperFile != null && !this.taintWrapperFile.isEmpty())
				info.setTaintWrapper(new EasyTaintWrapper(new File(this.taintWrapperFile)));
			info.setSootConfig(new SootConfigForAndroid());
			
			AndroidSourceSinkManager sourceSinkManager = new AndroidSourceSinkManager
				(sources, sinks, callbackMethods, false,
				LayoutMatchingMode.MatchSensitiveOnly, layoutControls);
			sourceSinkManager.setAppPackageName(this.appPackageName);
			sourceSinkManager.setResourcePackages(this.resourcePackages);
			
			AndroidEntryPointCreator entryPointCreator = new AndroidEntryPointCreator
				(new ArrayList<String>(this.entrypoints));
			List<String> callbackMethodSigs = new ArrayList<String>();
			for (AndroidMethod am : this.callbackMethods)
				callbackMethodSigs.add(am.getSignature());
			entryPointCreator.setCallbackFunctions(callbackMethodSigs);
			
			info.computeInfoflow(path, entryPointCreator, new ArrayList<String>(),
					sourceSinkManager);
			return info.getResults();
		}
		catch (IOException ex) {
			throw new RuntimeException("Error processing taint wrapper file", ex);
		}
	}
	
}
