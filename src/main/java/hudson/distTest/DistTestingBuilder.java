package hudson.distTest;

import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Hudson.MasterComputer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.remoting.Callable;
import hudson.remoting.Future;
import hudson.remoting.VirtualChannel;
import hudson.slaves.SlaveComputer;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import java.io.File;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Target;
import org.apache.tools.ant.taskdefs.Javac;
import org.apache.tools.ant.taskdefs.optional.junit.FormatterElement;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitTask;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitTest;
import org.apache.tools.ant.types.Path;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Core class for Distributed Testing Plugin. This class take test source code
 * and run test always on one node.
 *
 * @author Miroslav Novak
 */
public class DistTestingBuilder extends Builder implements Serializable {

    private DistLocations[] distLocations = new DistLocations[0];
    private LibLocations[] libLocations = new LibLocations[0];
    private final boolean waitForNodes;
    private final boolean compileTests;
    private final String testDir;
    // where to copy junit and ant_junit libraries
    private final String lidDir = "lib";

    /**
     * Constructor for this build.
     *
     * @param distLocations locations of distribution directories and jar files
     * @param libLocations locations of libraries - jar of directory
     * @param testDir where resides directory with compiled tests
     * @param waitForNodes whether build should wait for busy executors on online nodes
     * @param compileTests whether compile tests sources - compiles all the java source classes in workspace
     */
    @DataBoundConstructor
    public DistTestingBuilder(DistLocations[] distLocations, LibLocations[] libLocations,
            String testDir, boolean waitForNodes, boolean compileTests) {
        this.distLocations = distLocations;
        this.libLocations = libLocations;
        this.waitForNodes = waitForNodes;
        this.testDir = testDir;
        this.compileTests = compileTests;

    }

    /**
     * There is a necessity to lock executors on nodes which will be used for testing.
     * Only execution in a synchronized section doesn't lock executors which are idle.
     * So one task is send to each executor and that definitely lock it.
     * This operation will get the executor at the beginning of the synchronized section
     * where is our log. (method entrySynchronizedSection())
     *
     * @param label the assigned label
     */
    private LockingTasks lockExecutors(Label label, AbstractBuild build) throws IOException, InterruptedException {


        ArrayList<java.util.concurrent.Future<FreeStyleBuild>> lockBuildList = new ArrayList<java.util.concurrent.Future<FreeStyleBuild>>();

        ArrayList<FreeStyleProject> fspList = new ArrayList<FreeStyleProject>();

        ArrayList<Node> nodeList = new ArrayList<Node>();

        Computer c = null;

        for (Node n : label.getNodes()) {

            c = n.toComputer();

            if (c.isOnline() && c.isIdle() && c instanceof SlaveComputer) {

                nodeList.add(n);

                for (Executor e : c.getExecutors()) {

                    if (e.isIdle()) {

                        String lockProjectName = "Lock-" + build.getProject().getName()
                                + "-" + n.getDisplayName() + "-" + e.getDisplayName();

                        Hudson hudson = Hudson.getInstance();

                        // there could stay some projects from the last run
                        for (hudson.model.Project p : hudson.getProjects()) {

                            if (lockProjectName.equalsIgnoreCase(p.getName())) {

                                p.delete();

                            }

                        }

                        FreeStyleProject project = hudson.createProject(FreeStyleProject.class, lockProjectName);

                        final String projectName = build.getProject().getName();

                        project.getBuildersList().add(new org.jvnet.hudson.test.TestBuilder() {

                            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                                    BuildListener listener) throws InterruptedException, IOException {

                                boolean isParentProjectStillAlive = false;
                                try {

                                    while (true) {

                                        isParentProjectStillAlive = false;

                                        for (hudson.model.Project project : Hudson.getInstance().getProjects()) {
                                            listener.getLogger().println("project: " + project.getName());
                                            if (projectName.equalsIgnoreCase(project.getName()) && project.isBuilding()) {

                                                isParentProjectStillAlive = true;

                                            }

                                        }

                                        if (!isParentProjectStillAlive) {

                                            build.getProject().delete();
                                            return true;

                                        }

                                        listener.getLogger().println("zije parent: " + isParentProjectStillAlive);
                                        Thread.sleep(1000);
                                    }

                                } catch (InterruptedException ex) {
                                    // remove project if someone cancel it
                                    build.getProject().delete();

                                }

                                return true;
                            }
                        });


                        project.setAssignedNode(n);

                        java.util.concurrent.Future<FreeStyleBuild> f = project.scheduleBuild2(0);

                        lockBuildList.add(f);

                        fspList.add(project);

                    }
                }
            }
        }

        return new LockingTasks(lockBuildList, fspList, nodeList);

    }

    /**
     * Method that actually run the whole build step and controll it
     *
     * @param build
     * @param launcher
     * @param listener
     * @return
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, final BuildListener listener) {

        try {

            Label label = build.getProject().getAssignedLabel();

            if (label == null) {
                throw new Exception("Set label in the \"Tie this project to a node\" section +"
                        + " in the project configaration.");
            }

            if (build.getBuiltOn().toComputer() instanceof MasterComputer) {

                throw new Exception("Distributed testing task cannot be perform on master. Please change the label.");

            }

            if (testDir == null || "".equals(testDir)) {

                throw new Exception("Directory with tests must be set.");

            }

            if (label.getNodes().size() < 2) {
                throw new Exception("Number of nodes in label "
                        + label + " must be more than one. Distributed Testing was cancelled.");
            }

            // wait for freeing executors if some are busy
            // don't wait for offline nodes
            if (isWaitForNodes()) {

                waitForNodes(build, listener);

            }

            LockingTasks lockingTasks = lockExecutors(label, build);

            // copy the necessary libraries needed to run and compile tests - creates lib directory
            //listener.getLogger().print("Copying libraries: ");
            copyLibraries(build.getWorkspace().child(lidDir));
            listener.getLogger().println("finished");
            if (isCompileTests()) {
                listener.getLogger().print("Compiling sources on slave " + build.getBuiltOnStr() + " :");
                compileTests(build);
                listener.getLogger().println("finished");
            }

            // LOAD ALL TEST TO THE QUEUE - String class name (example: "helloworld.Hello")
            String directoryWithCompiledTests = null;

            if (compileTests) {

                directoryWithCompiledTests = "tests";

            } else {

                directoryWithCompiledTests = getTestDir();

            }

            if ("\\".equals(getFileSeparatorForNode(build.getBuiltOn()))) {

                directoryWithCompiledTests = directoryWithCompiledTests.replace("/", "\\");

            }

            final LinkedList<String> tests = findTestsInProjectWorkspace(build.getWorkspace().child(directoryWithCompiledTests));
            listener.getLogger().println();
            listener.getLogger().println("Print all tests files:");
            for (Iterator<String> it = tests.iterator(); it.hasNext();) {
                listener.getLogger().println(it.next());
            }
            listener.getLogger().println();
            // create directory for test results because ant is not able to do so
            build.getWorkspace().child("results").mkdirs();

            final ArrayList<String> listOfNodes = new ArrayList<String>();

            for (Node n : lockingTasks.getNodeList()) {

                listOfNodes.add(n.getDisplayName());

            }
            // add this node too
            if (build.getBuiltOn().toComputer() instanceof SlaveComputer) {

                listOfNodes.add(build.getBuiltOnStr());

            }

            listener.getLogger().println();
            listener.getLogger().println("Lists all test nodes:");
            for (String node : listOfNodes) {
                listener.getLogger().println(node);
            }
            listener.getLogger().println();

            final String projectName = build.getProject().getName();

////////////////////////// CALL SLAVE (and let him to choose test and node to run next)
            listener.getLogger().println("Call build-on slave to run tests");
            build.getWorkspace().act(new FileCallable<Boolean>() {

                public Boolean invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {

                    final TreeMap<String, Future<Boolean>> mapNodeFuture = new TreeMap<String, Future<Boolean>>();

                    for (String n : listOfNodes) {
                        mapNodeFuture.put(n, null);
                    }

                    Future<Boolean> future = null;
                    listener.getLogger().println("Start sending tests");
                    long startTime = System.currentTimeMillis();
                    while (!tests.isEmpty()) {

                        for (String n : mapNodeFuture.keySet()) {
                            final String nodeName = n;

                            if ((future = mapNodeFuture.get(n)) == null) {
                                // run tests on them
                                final String testClass = tests.poll();
                                if (testClass != null) {

//                                listener.getLogger().println("Call master to send test " +testClass+ " to node " + nodeName);
                                    ///////////// call MASTER and made him to call another slave (needs node and test name)
                                    future = channel.callAsync(new Callable<Boolean, Throwable>() {

                                        public Boolean call() throws Throwable {

                                            AbstractBuild build = null;

                                            for (hudson.model.Project p : Hudson.getInstance().getProjects()) {

                                                if (p instanceof FreeStyleProject && projectName.equals((String) p.getName())) {

                                                    FreeStyleProject f = (FreeStyleProject) p;

                                                    build = f.getLastBuild();

                                                }
                                            }

                                            Node node = Hudson.getInstance().getNode(nodeName);
                                            String workspaceOnTestNode = getWorkspaceForThisProjectOnNode(node, build);
//                                         listener.getLogger().println("Call slave to execute test " +testClass+ " " + nodeName);
                                            //////////////// CALL SLAVE (needs node and test)
                                            Boolean b = new FilePath(node.getChannel(), workspaceOnTestNode).act(new FileCallable<Boolean>() {

                                                public Boolean invoke(File file, VirtualChannel channel) throws IOException, InterruptedException {
                                                    listener.getLogger().println("Run test " + testClass + " on node: " + nodeName);
                                                    Project project = null;

                                                    try {

                                                        File baseDir = file;

                                                        File resultsDir = new File(baseDir, "results");

//                    listener.getLogger().println("BaseDir is " + baseDir.getAbsolutePath());

                                                        project = new Project();

//                    DefaultLogger consoleLogger = new DefaultLogger();
//                    consoleLogger.setErrorPrintStream(listener.getLogger());
//                    consoleLogger.setOutputPrintStream(listener.getLogger());
//                    consoleLogger.setMessageOutputLevel(Project.MSG_INFO);
//
//                    project.addBuildListener(consoleLogger);

                                                        project.init();
                                                        project.setBaseDir(baseDir);
                                                        JUnitTest test = new JUnitTest(testClass, true, true, false);
//                                        test.setOutfile("Test-" + testClass);
                                                        test.setTodir(resultsDir);
                                                        FormatterElement fe = new FormatterElement();
                                                        FormatterElement.TypeAttribute ta = new FormatterElement.TypeAttribute();
                                                        ta.setValue("xml");
                                                        fe.setType(ta);
                                                        test.addFormatter(fe);

                                                        JUnitTask junit = null;
                                                        try {
                                                            junit = new JUnitTask();
                                                        } catch (Exception ex) {
                                                            ex.printStackTrace(listener.getLogger());
                                                        }
                                                        junit.addTest(test);
                                                        junit.setProject(project);
                                                        junit.init();
                                                        Path p = junit.createClasspath();
                                                        p.add(p.systemClasspath);

                                                        File fileTest = null;

                                                        if (compileTests) {

                                                            fileTest = new File(baseDir, "tests");

                                                        } else {

                                                            fileTest = new File(baseDir, getTestDir());

                                                        }

                                                        File libDirFile = new File(baseDir, lidDir);
                                                        p.createPathElement().setLocation(libDirFile);
                                                        for (File l : libDirFile.listFiles()) {
                                                            if (l.isFile()) {
                                                                p.createPathElement().setLocation(l);
//                            listener.getLogger().println("lokace: " + l);
                                                            }
                                                        }

                                                        p.createPathElement().setLocation(fileTest);

                                                        File distsDirFile = null;

                                                        for (DistLocations distLoc : getDistLocations()) {

                                                            distsDirFile = new File(baseDir, distLoc.getDistDir());

                                                            p.createPathElement().setLocation(new File(baseDir, distLoc.getDistDir()));
//                        listener.getLogger().println("lokace: " + new File(baseDir, distLoc.getDistDir()).getAbsolutePath());
                                                            if (distsDirFile.isDirectory()) {

                                                                for (File f : distsDirFile.listFiles()) {
                                                                    //if file then add too
                                                                    if (!f.isDirectory()) {
                                                                        p.createPathElement().setLocation(f);
//                                    listener.getLogger().println("lokace: " + f);
                                                                    }
                                                                }
                                                            }

                                                        }

                                                        File libDirFile2 = null;

                                                        for (LibLocations libLoc : getLibLocations()) {

                                                            libDirFile2 = new File(baseDir, libLoc.getLibDir());

                                                            p.createPathElement().setLocation(libDirFile2);
//                        listener.getLogger().println("lokace: " + libDirFile2);
                                                            if (libDirFile2.isDirectory()) {

                                                                for (File f2 : libDirFile2.listFiles()) {
                                                                    //if file then add too
                                                                    if (!f2.isDirectory()) {
                                                                        p.createPathElement().setLocation(f2);
//                                    listener.getLogger().println("lokace: " + f2);
                                                                    }
                                                                }
                                                            }

                                                        }

                                                        Target target = new Target();
                                                        target.setName("test");
                                                        target.addTask(junit);
                                                        project.addTarget("test", target);
                                                        project.executeTarget("test");

                                                    } finally {

                                                        project = null;
                                                        System.gc();
                                                    }

                                                    return true;
                                                }
                                            });

                                            /// returning from call on test slave
                                            ///////////////////////////
                                            /// now back on master

                                            return true;

                                        }
                                    });
                                    ///// here on build-on slave again
                                    mapNodeFuture.put(n, future);
                                }
                            } else {

                                try {

                                    // did test finished - if yes then free then node for another test
                                    if (future.get(100, TimeUnit.MILLISECONDS)) {
                                        mapNodeFuture.put(n, null);
                                    }
                                } catch (ExecutionException ex) {
                                    ex.printStackTrace(listener.getLogger());


                                } catch (TimeoutException ex) {
                                    //ignore timeouts
                                }


                            }

                            future = null;

                        }
                    }

                    // it can happen that some tests are still in progress
                    // in this case we have to wait for them
                    listener.getLogger().println("Waiting for last tests results");

                    for (String n : mapNodeFuture.keySet()) {

                        if ((future = mapNodeFuture.get(n)) != null) {
                            try {
                                future.get();
                            } catch (ExecutionException ex) {
                                Logger.getLogger(DistTestingBuilder.class.getName()).log(Level.SEVERE, null, ex);
                            }

                        }

                    }

                    long time = System.currentTimeMillis() - startTime;
                    listener.getLogger().println("Testing time: " + time);

                    return true;

                }
            });



        } catch (Throwable ex) {
            ex.printStackTrace(listener.getLogger());
        } finally {
        }

        return true;
    }

    public String getWorkspaceForThisProjectOnNode(Node node, AbstractBuild build) throws IOException, Exception {

        String path = null;

        if (node != null && node.toComputer().isOnline()) {

            path = node.getRootPath().getRemote() + getFileSeparatorForNode(node)
                    + "workspace" + getFileSeparatorForNode(node) + build.getProject().getName();

        }

        return path;
    }

   
    // overrided for better type safety.
    // if your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Gets the FilePath(for jar) on library where the class resides. It is used
     * when we need to copy some class to the lib directory in the project workspace.
     *
     * @param class name f.e. "helloword.Hello"
     * @return FilePath to the library where this class resides
     */
    private FilePath getFilePathOnMasterForClass(String className) throws ClassNotFoundException, URISyntaxException {
        FilePath f = null;
        Class cls = this.getClass().getClassLoader().loadClass(className);
        ProtectionDomain pDomain = cls.getProtectionDomain();
        CodeSource cSource = pDomain.getCodeSource();
        URL url = cSource.getLocation(); // file:/c:/almanac14/examples/
        f = new FilePath(new File(url.toURI()));

        return f;
    }

    /**
     * @return the waitForNodes
     */
    public boolean isWaitForNodes() {
        return waitForNodes;
    }

    /**
     * Copies junit.jar and ant_junit.jar to toWhere
     * directory in the project workspace.
     *
     * @param toWhere where to copy junit and ant_junit libraries
     * @return true if all is ok
     * @throws IOException
     * @throws InterruptedException
     * @throws Exception
     */
    private void copyLibraries(FilePath toWhere) throws IOException, InterruptedException, Exception {
        if (!toWhere.exists()) {
            toWhere.mkdirs();
        }
        FilePath junit = getFilePathOnMasterForClass("org.junit.runner.JUnitCore");
        FilePath antJunit = getFilePathOnMasterForClass("org.apache.tools.ant.taskdefs.optional.junit.JUnitTask");
        FilePath ant = getFilePathOnMasterForClass("org.apache.tools.ant.Project");
        junit.copyTo(toWhere.child(junit.getName()));
        antJunit.copyTo(toWhere.child(antJunit.getName()));
        ant.copyTo(toWhere.child(ant.getName()));

    }

    /**
     * Search in the directory with tests in the project's workspace and put all
     * the classes with "test" string in the name (case insensitive) to the queue.
     * @param dirWithTest directory with compiled test classes
     * @return list of test's classes names
     * @throws IOException
     * @throws Exception
     */
    private LinkedList<String> findTestsInProjectWorkspace(FilePath dirWithTest) throws IOException, Exception {
        TestFilePathVisitor filePathVisitor = new TestFilePathVisitor();
        // scanner which go through the directory with tests and pass to TestVisitor
        FilePathDirScanner filePathDirScanner = new FilePathDirScanner();
        filePathDirScanner.scan(dirWithTest, filePathVisitor);
        if (filePathVisitor.getListOfTests().size() <= 0) {
            throw new Exception("Could not find any test classes in the directory: " + dirWithTest);
        }
        return filePathVisitor.getListOfTests();
    }

    /**
     * @return the testDir
     */
    public String getTestDir() {
        return testDir;
    }

    /**
     * Return all nodes which are idle(all executors are free) and online. Warning:
     * it doesn't count build-on node.
     *
     * @param label Label assigned to this project
     * @return list of nodes
     */
    private Map<Node, Future<Boolean>> getAllUseableNodes(Label label) {

        Map<Node, Future<Boolean>> map = new HashMap<Node, Future<Boolean>>();

        for (Node node : label.getNodes()) {

            if (node.toComputer().isOnline() && node.toComputer().isIdle()
                    && node.toComputer() instanceof SlaveComputer) {

                map.put(node, null);

            }
        }

        return map;
    }

    /**
     * When user set "compile tests" check box then all sources in the workspace
     * will be compiled. All necessary libraries must be present in the "lib" directory.
     * Compiles test classes are saved in the specified "directory with tests" directory.
     *
     * @param build build of this project
     * @throws IOException
     * @throws InterruptedException
     */
    private void compileTests(AbstractBuild build) throws IOException, InterruptedException {
        // get workspace filapath on the built-on slave and compile
//        listener.getLogger().println();
//        listener.getLogger().println("workspace je: " + build.getWorkspace().getRemote());

        build.getWorkspace().act(new FileCallable<Boolean>() {

            public Boolean invoke(File f, VirtualChannel channel) {

                Project project = null;

//                listener.getLogger().println("base dir is : " + f.getAbsolutePath());

                try {
                    // create dir for compiled tests
                    File testsDir = null;

                    testsDir = new File(f, "tests");

                    if (!testsDir.exists()) {
                        testsDir.mkdir();
                    }

                    project = new Project();

                    project.setBaseDir(f);

                    Target compTestTarget = new Target();
                    compTestTarget.setName("compile-tests");

                    Javac javacTask = new Javac();
                    javacTask.setClasspath(Path.systemClasspath);

                    // load libraries
                    Path libs = javacTask.createClasspath();
                    File libDirFile = new File(f, lidDir);
                    libs.createPathElement().setLocation(libDirFile);
                    for (File l : libDirFile.listFiles()) {
                        if (l.isFile()) {
                            libs.createPathElement().setLocation(l);
                        }
                    }
                    libDirFile = null;
                    for (LibLocations libLoc : getLibLocations()) {

                        libDirFile = new File(f, libLoc.getLibDir());

                        libs.createPathElement().setLocation(new File(f, libLoc.getLibDir()));
 
                        if (libDirFile.isDirectory()) {

                            for (File file : libDirFile.listFiles()) {

                                if (file.isFile()) {

                                    libs.createPathElement().setLocation(new File(f, libLoc.getLibDir() + "/" + file.getName()));
       
                                }
                            }
                        }
                    }

                    // load dists
                    Path dists = javacTask.createClasspath();

                    File distsDirFile = null;

                    for (DistLocations distLoc : getDistLocations()) {

                        distsDirFile = new File(f, distLoc.getDistDir());

                        dists.createPathElement().setLocation(new File(f, distLoc.getDistDir()));
                    
                        if (distsDirFile.isDirectory()) {

                            for (File file : distsDirFile.listFiles()) {

                                if (file.isFile()) {
                                    dists.createPathElement().setLocation(new File(f, distLoc.getDistDir() + file.getName()));
                    
                                }
                            }
                        }

                    }

                    Path srcPath = new Path(project);

                    srcPath.setLocation(new File(f, testDir));

                    javacTask.setSrcdir(srcPath);
                    
                    javacTask.setDestdir(testsDir);

                    javacTask.setProject(project);

                    compTestTarget.addTask(javacTask);

                    project.addTarget("compile-tests", compTestTarget);

                    project.executeTarget("compile-tests");

                } finally {
                    project = null;
                    System.gc();
                }

                return true;
            }
        });
    }

    /**
     * @return the compileTests
     */
    public boolean isCompileTests() {
        return compileTests;
    }

    /**
     * Gets file separator of the given node. Taken from System.getProperty("file.separator")
     * on the node
     * @param node which node
     * @return separator "/" or "\\"
     * @throws IOException
     * @throws Exception
     */
    private String getFileSeparatorForNode(Node node) throws IOException, Exception {
        return node.getChannel().call(new Callable<String, Exception>() {

            public String call() throws Exception {
                return System.getProperty("file.separator");
            }
        });

    }

    /**
     * @return the distLocations
     */
    public DistLocations[] getDistLocations() {
        return distLocations;
    }

    /**
     * @return the libLocations
     */
    public LibLocations[] getLibLocations() {
        return libLocations;
    }

    /**
     * If wait for nodes/executors was checked then wait for all executors
     * on all nodes which are online and busy.
     * @param label Label which was set for this project/build
     * @throws InterruptedException
     */
    private void waitForNodes(AbstractBuild build, BuildListener listener) throws InterruptedException {

        Label label = build.getProject().getAssignedLabel();

        boolean allFree;

        Set<Node> nodes = label.getNodes();

        while (true) {

            allFree = true;

            for (Node n : nodes) {

                if (n.toComputer().isOnline() && !n.toComputer().isIdle()
                        && !build.getBuiltOnStr().equalsIgnoreCase(n.getNodeName())) {

                    allFree = false;

                }
            }

            if (allFree) {

                listener.getLogger().println("All nodes are free now.");

                return;

            }

            listener.getLogger().println("Waiting for all other nodes in label " + label);

            Thread.sleep(1000);

        }
    }

    /**
     * Descriptor for {@link DistTestBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // this marker indicates Hudson that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckDistDir(@QueryParameter String value) throws IOException, ServletException, InterruptedException {
            if (value.length() == 0) {
                return FormValidation.warning("If you don't set the distribution path "
                        + "then tests will search for the compiled classes in the standard classpath of the slave");
            }

            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Distibuted Testing";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
            // to persist global configuration information,
            // set that to properties and call save().
            save();
            return super.configure(req, o);
        }
    }

    @ExportedBean
    public static final class DistLocations implements Serializable {

        @Exported
        public final String distDir;

        @DataBoundConstructor
        public DistLocations(String distDir) {
            this.distDir = distDir;
        }

        public String getDistDir() {
            return distDir;
        }
    }

    @ExportedBean
    public static final class LibLocations implements Serializable {

        @Exported
        public final String libDir;

        @DataBoundConstructor
        public LibLocations(String libDir) {
            this.libDir = libDir;
        }

        public String getLibDir() {
            return libDir;
        }
    }
}
