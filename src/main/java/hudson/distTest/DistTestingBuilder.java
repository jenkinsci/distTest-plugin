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
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Executable;
import hudson.remoting.Callable;
import hudson.remoting.Future;
import hudson.remoting.VirtualChannel;
import hudson.slaves.SlaveComputer;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.CancellationException;
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
 * Core class for Distributed Testing Plugin. This class take test source code,
 * compile it and run test always on one node.
 *
 * @author Miroslav Novak
 */
public class DistTestingBuilder extends Builder implements Serializable {

    private DistLocations[] distLocations = new DistLocations[0];
    private LibLocations[] libLocations = new LibLocations[0];
    private final boolean waitForNodes;
    private final boolean compileTests;
    private final String testDir;
    // root, dist, tests, results
    private final Map<String, Map<String, String>> mapNodeWorkspacePaths = new HashMap<String, Map<String, String>>();
    public BuildListener listener = null;


    /**
     * Constructor for this build.
     *
     * @param distLocations locations of distribution directories and jar files
     * @param libLocations locations of libraries - jar of directory
     * @param testDir where resides directory with compiled tests
     * @param waitForNodes whether build should wait for busy executors on online nodes
     * @param compileTests whether compile tests sources - compiles all the jave source classes in workspace
     */
    @DataBoundConstructor
    public DistTestingBuilder(DistLocations[] distLocations, LibLocations[] libLocations,
            String testDir, boolean waitForNodes, boolean compileTests) {
        this.distLocations = distLocations;
        this.libLocations = libLocations;
        this.waitForNodes = waitForNodes;
        this.testDir = testDir;
        this.compileTests = compileTests;
        Hudson h = Hudson.getInstance();

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
        this.listener = listener;

        LinkedList<Node> nodesList = new LinkedList<Node>();
        for (Node n : build.getProject().getAssignedLabel().getNodes()) {
            nodesList.add(n);
        }
        return entrySynchronizedSectionForNode(nodesList, build, launcher);
    }

    /**
     * This perform2 method is run in the synchronized section because all nodes
     * in label are shared resources we need to run test.
     *
     * @param build build representing this build
     * @param launcher launcher for this build
     * @return
     */
    public boolean perform2(AbstractBuild build, Launcher launcher) {

        try {

            Label label = build.getProject().getAssignedLabel();
            if (label == null) {
                throw new Exception("Set label in the \"Tie this project to a node\" section +"
                        + " in the project configaration.");
            }
            for (Node node : label.getNodes()) {
                if (node.toComputer() instanceof Hudson.MasterComputer) {
                    throw new Exception("Label " + label + " cannot contain the \"master\" computer.");
                }
            }
            if (label.getTotalConfiguredExecutors() < 2) {
                throw new Exception("Number of executors in label "
                        + label + " must be more than one. Distributed Testing was cancelled.");
            }

            // wait for freeing executors if some are busy
            // don't wait for offline nodes
            // (this executor is not idle (so +1))
            waitForNodes(label);

            // if there are no executors on which is possible to run tests then trow exception
            if (label.getIdleExecutors() < 1 && !waitForNodes) {
                throw new Exception("There are no idle/free executors in label : " + label
                        + " on which could be possible to run tests. There must be at least 2 free exeutors. "
                        + "This build is cancelled. Check \"Wait for nodes which are busy\" box "
                        + "if you want to wait for freeing nodes. It is possible that the nodes "
                        + "in this label are offline too.");
            }

            // sends a project to all executors which are idle - after it it's
            // execution they're not able to accept any tasks
            Computer c = null;
            DistForkTask t = null;
            Hudson h = Hudson.getInstance();
            for (Node n : label.getNodes()) {
                c = n.toComputer();
                if (c.isOnline()) {
                    for (Executor e : c.getExecutors()) {
                        if (e.isIdle()) {
                            t = createTask(n.getSelfLabel());
                            // run and wait for the completion
                            Queue q = h.getQueue();
                            Future<Executable> f = (Future<Executable>) q.schedule(t, 0).getFuture();
                            try {
                                f.get();
                            } catch (CancellationException ex) {
                                // don't care about cancelation
                            } catch (InterruptedException ex) {
                                // if the command itself is aborted, cancel the execution
                                // don't care this too
                            }
                        }
                    }
                }
            }

            listener.getLogger().println();
            listener.getLogger().println("Lists all nodes in label " + label + " :");
            for (Node node : label.getNodes()) {
                listener.getLogger().println(node.getNodeName());
            }

            copyDists(build);

            // copy the necessary libraries needed to run and compile tests - creates lib directory
            listener.getLogger().print("Copying libraries: ");
            copyLibraries(build.getWorkspace().child("lib"));
            listener.getLogger().println("finished");
            if (isCompileTests()) {
                listener.getLogger().print("Compiling sources on slave " + build.getBuiltOnStr() + " :");
                compileTests(build);
                listener.getLogger().println("finished");
            }


            // LOAD ALL TEST TO THE QUEUE - String class name (example: "helloworld.Hello")
            LinkedList<String> tests = findTestsInProjectWorkspace(build.getWorkspace().child(testDir));
            listener.getLogger().println("Print all tests files:");
            for (Iterator<String> it = tests.iterator(); it.hasNext();) {
                listener.getLogger().println(it.next());
            }

            // MAKE A MAP (NODENAME -> (DIR -> DIRPATH)) FOR EACH NODE
            // master is not there even if he is in the label
            fillMapNodeWorkSpace(build);

            // create directory for test results because ant is not able to do so
            build.getWorkspace().child("results").mkdirs();

            // find all executors which are online
            Map<Executor, Future<Boolean>> mapExecutorFuture = getAllUseableExetors(label);
            // list them
            listener.getLogger().println("Lists all useable executors: ");
            for (Executor e : mapExecutorFuture.keySet()) {
                listener.getLogger().println(e.toString() + "is idle: " + e.isIdle());
            }

            // run tests on them
            String testClassName = null;
            boolean noFreeExecutors = true;
            Future<Boolean> future = null;
            FilePath testFilePathOnNode = null;
            while (!tests.isEmpty()) {
                for (Executor e : mapExecutorFuture.keySet()) {
                    // if executor is idle then run a test on it
                    if (e.getOwner().isOnline() && e.isIdle() && (testClassName = tests.pollFirst()) != null) {
                        testFilePathOnNode = createFilePathOnNodeForTest(e.getOwner().getNode(), testClassName);
                        mapExecutorFuture.put(e, runTestOnNode(e.getOwner().getNode(), testClassName, testFilePathOnNode));
                    }
                }
                // no free executors then try to find (if no tests end)
                while (noFreeExecutors && (!tests.isEmpty())) {
                    for (Executor e : mapExecutorFuture.keySet()) {
                        try {
                            if ((future = mapExecutorFuture.get(e)) != null) {
                                future.get(100, TimeUnit.MILLISECONDS);
                                mapExecutorFuture.put(e, null);
                                noFreeExecutors = false;
                            }
                        } catch (TimeoutException ex) {
                            // when test not finished due to this timeout just go on
                        }
                    }
                }
            }
            // it can happen that some tests wait in the task queue
            // for the given executor - in this case we have to wait for them
            listener.getLogger().println("Waiting for last tests results");
            for (Executor e : mapExecutorFuture.keySet()) {
                if ((future = mapExecutorFuture.get(e)) != null) {
                    future.get();
                }
            }

            listener.getLogger().println("Copy test results back to the master's artifact directory");
            copyResults(build);


        } catch (Throwable ex) {
            ex.printStackTrace(listener.getLogger());
        } finally {
            //print paths in mapNodeWorkspacePaths
            for (Node n : build.getProject().getAssignedLabel().getNodes()) {
                listener.getLogger().println(n.getNodeName());
            }
            for (String nodeName : mapNodeWorkspacePaths.keySet()) {
                listener.getLogger().println("Paths for node " + nodeName);
                Map<String, String> paths = mapNodeWorkspacePaths.get(nodeName);
                for (String dir : paths.keySet()) {
                    listener.getLogger().print("Dir: " + dir + " path: " + paths.get(dir));
                    listener.getLogger().println();
                }
            }
        }
        return true;
    }


    /**
     * Run test on the given node - runs ant programatically on this node
     *
     * @param node node where to run this test
     * @param testClassName name of the class f.e. "helloworld.Hello"
     * @param testFilePath path on node where to find test file class
     * @return future whether the test finished
     * @throws IOException
     * @throws InterruptedException
     */
    public Future<Boolean> runTestOnNode(Node node, final String testClassName, FilePath testFilePath) throws IOException, InterruptedException {
        final String nodeName = node.getNodeName();
        Future<Boolean> result = null;

        listener.getLogger().println("Run test " + testClassName + " on node " + nodeName);
        result = testFilePath.actAsync(new FileCallable<Boolean>() {

            public Boolean invoke(File file, VirtualChannel channel) throws IOException, InterruptedException {
                Project project = null;
                try {
                    File baseDir = new File(mapNodeWorkspacePaths.get(nodeName).get("root"));
                    File resultsDir = new File(baseDir, "results");

//                listener.getLogger().println("BaseDir is " + baseDir.getAbsolutePath());
                    project = new Project();
//                    DefaultLogger consoleLogger = new DefaultLogger();
//                    consoleLogger.setErrorPrintStream(System.err);
//                    consoleLogger.setOutputPrintStream(System.out);
//                    consoleLogger.setMessageOutputLevel(Project.MSG_DEBUG);
//                    project.addBuildListener(consoleLogger);
                    project.init();
                    project.setBaseDir(baseDir);
                    JUnitTest test = new JUnitTest(testClassName, true, true, false);
                    test.setOutfile("Test-" + testClassName);
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

                    File fileTest = new File(mapNodeWorkspacePaths.get(nodeName).get("tests"));
                    File fileDist = new File(mapNodeWorkspacePaths.get(nodeName).get("dist"));
                    if (!fileDist.exists()) {
                        throw new IOException("The Directory or the jar file " + fileDist.getAbsolutePath()
                                + " doesn't exist on the slave.");
                    }
                    p.createPathElement().setLocation(fileTest);
                    p.createPathElement().setLocation(fileDist);
                    for (File d : fileDist.listFiles()) {
                        if (d.isFile()) {
                            p.createPathElement().setLocation(d);
                        }
                    }
                    File fileLib = new File(mapNodeWorkspacePaths.get(nodeName).get("lib"));
                    p.createPathElement().setLocation(fileLib);
                    for (File l : fileLib.listFiles()) {
                        if (l.isFile()) {
                            p.createPathElement().setLocation(l);
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

        return result;
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
    private FilePath getFilePathOnMasterForClass(String className) {
        FilePath f = null;
        try {
            Class cls = this.getClass().getClassLoader().loadClass(className);
            ProtectionDomain pDomain = cls.getProtectionDomain();
            CodeSource cSource = pDomain.getCodeSource();
            URL url = cSource.getLocation(); // file:/c:/almanac14/examples/
            f = new FilePath(new File(url.toURI()));
        } catch (URISyntaxException ex) {
            ex.printStackTrace(listener.getLogger());
        } catch (ClassNotFoundException ex) {
            ex.printStackTrace(listener.getLogger());
        }
        return f;
    }

    /**
     * @return the waitForNodes
     */
    public boolean isWaitForNodes() {
        return waitForNodes;
    }

    /**
     * Copies test results from "results" directory to the artifact directory and zip it
     * into "test_results.zip" file.
     *
     * @param build
     * @throws IOException
     * @throws InterruptedException
     */
    private void copyResults(AbstractBuild build) throws IOException, InterruptedException {
        final String buildOnNodeName = build.getBuiltOnStr();
        final String artifactDir = build.getArtifactsDir().getAbsolutePath();
        FilePath workspace = build.getWorkspace();
        workspace.act(new FileCallable<Void>() {

            public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                File zip = new File(f, "test_results.zip");
                FileOutputStream out =
                        new FileOutputStream(zip);
                new FilePath(new File(mapNodeWorkspacePaths.get(buildOnNodeName).get("results"))).zip(out, "*.xml");
                new FilePath(zip).copyTo(new FilePath(channel, artifactDir).child(zip.getName()));
                return null;
            }
        });
    }

    /**
     * Copies all the libraries needed for test compilation and running test to the "lib"
     * directory in the project workspace.
     *
     * @param toWhere where resides the project's lib directory
     * @return true if all is ok
     * @throws IOException
     * @throws InterruptedException
     * @throws Exception
     */
    private boolean copyLibraries(FilePath toWhere) throws IOException, InterruptedException, Exception {
        if (!toWhere.exists()) {
            toWhere.mkdirs();
        }
        FilePath junit = getFilePathOnMasterForClass("org.junit.runner.JUnitCore");
        FilePath antJunit = getFilePathOnMasterForClass("org.apache.tools.ant.taskdefs.optional.junit.JUnitTask");
        FilePath ant = getFilePathOnMasterForClass("org.apache.tools.ant.Project");
        junit.copyTo(toWhere.child(junit.getName()));
        antJunit.copyTo(toWhere.child(antJunit.getName()));
        ant.copyTo(toWhere.child(ant.getName()));
        // if libdir was set then try to find and copy libraries
        if (getLibLocations() != null) {
            for (LibLocations lib : getLibLocations()) {
                String normalizeLibDir = normalizeRelPathForNode(Hudson.MasterComputer.currentComputer().getNode(), lib.getLibDir());
                File libDirFile = new File(normalizeLibDir);
                if (libDirFile.exists()) {
                    if (libDirFile.isDirectory()) {
                        //copyrec
                        new FilePath(libDirFile).copyRecursiveTo(toWhere);
                    } else {
                        //copy file
                        new FilePath(libDirFile).copyTo(toWhere.child(libDirFile.getName()));
                    }

                }
            }
        }
        return true;
    }

    /**
     * Search in the directory with tests in the project's workspace and put all
     * the classes with "test" string in the name (case insensitive) to the queue.
     * @param dirWithTest diretory with compiled test classes
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
     * Creates file path for the given test file on the node.
     *
     * @param node node for which this file path is
     * @param testClassName name of the test class
     * @return filepath on the test on the node
     */
    private FilePath createFilePathOnNodeForTest(Node node, String testClassName) {
        StringTokenizer st = new StringTokenizer(testClassName, ".");
        FilePath testFilePathOnNode =
                new FilePath(node.getChannel(),
                mapNodeWorkspacePaths.get(node.getNodeName()).get("tests"));
        for (int j = 0; j < st.countTokens() - 1; j++) {
            testFilePathOnNode = testFilePathOnNode.child(st.nextToken());
        }
        return testFilePathOnNode.child(st.nextToken().concat(".class"));
    }

    /**
     * @return the testDir
     */
    public String getTestDir() {
        return testDir;
    }

    /**
     * Find all executors in the specified label which are on online nodes.
     *
     * @param label Label assigned to this project
     * @return list of Executors
     */
    private Map<Executor, Future<Boolean>> getAllUseableExetors(Label label) {
        Map<Executor, Future<Boolean>> map = new HashMap<Executor, Future<Boolean>>();
        for (Node node : label.getNodes()) {
            if (node.toComputer().isOnline()) {
                for (Executor e : node.toComputer().getExecutors()) {
                    map.put(e, null);
                }
            }
        }
        return map;
    }

    /**
     * Fills the map (nodeName -> (directory -> path)) which is used on slaves to
     * find files and directories in their NFS workspace.
     *
     * @param build this build
     * @throws IOException
     * @throws Exception
     */
    private void fillMapNodeWorkSpace(AbstractBuild build) throws IOException, Exception {
        Label label = build.getProject().getAssignedLabel();
        HashMap<String, String> paths = null;
        FilePath projectWorkspaceOnNode = null;
        // name of the workspace on node
        String workspace = "workspace";
        for (Node node : label.getNodes()) {
            // we don't want to use master even if he is in the label
            if (node.toComputer() instanceof SlaveComputer && node.toComputer().isOnline()) {
                paths = new HashMap<String, String>();
                projectWorkspaceOnNode = new FilePath(node.getRootPath(), workspace).child(build.getProject().getName());
                paths.put("root", projectWorkspaceOnNode.getRemote());
                paths.put("lib", projectWorkspaceOnNode.child("lib").getRemote());
                if (testDir == null || "".equals(testDir)) {
                    paths.put("tests", projectWorkspaceOnNode.child("tests").getRemote());
                } else {
                    paths.put("tests", projectWorkspaceOnNode.child(normalizeRelPathForNode(node, testDir)).getRemote());
                }
                paths.put("results", projectWorkspaceOnNode.child("results").getRemote());
                paths.put("dist", projectWorkspaceOnNode.child("dist").getRemote());
                mapNodeWorkspacePaths.put(node.getNodeName(), paths);
            }
        }
    }

    /**
     * Return file path on the master file system to the specified directory or file.
     * @param distDir Absolute or relative (to the project workspace) path to dist. dir.
     * @return file path to the dist. directory or file or null when not found
     * @throws IOException
     * @throws InterruptedException
     */
    private FilePath getDistDirFilePath(String distDir) throws IOException, InterruptedException {
        FilePath distDirFilePath = new FilePath(new File(distDir));
        if (!distDirFilePath.exists()) {
            return null;
        }
        return distDirFilePath;
    }

    /**
     * When user set "compile tests" checkbox then all sources in the workspace
     * will be compiled. All necessary libraries must be present in the "lib" directory.
     * Compiles test classes are saved in the specified "directory with tests" directory.
     *
     * @param build build of this project
     * @throws IOException
     * @throws InterruptedException
     */
    private void compileTests(AbstractBuild build) throws IOException, InterruptedException {
        // get workspace filapath on the built-on slave and compile

        build.getWorkspace().act(new FileCallable<Boolean>() {

            public Boolean invoke(File f, VirtualChannel channel) {
                Project project = null;
                try {
                    // create dir for compiled tests
                    File testsDir = null;
                    if (testDir == null || "".equals(testDir)) {
                        testsDir = new File(f, "tests");
                    } else {
                        testsDir = new File(f, testDir.replace("/", System.getProperty("file.separator")));
                    }
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
                    File libDirFile = new File(f, "lib");
                    libs.createPathElement().setLocation(libDirFile);
                    for (File l : libDirFile.listFiles()) {
                        if (l.isFile()) {
                            libs.createPathElement().setLocation(l);
                        }
                    }

                    // load dists
                    Path dists = javacTask.createClasspath();
                    File distsDirFile = new File(f, "dist");
                    dists.createPathElement().setLocation(distsDirFile);
                    for (File d : distsDirFile.listFiles()) {
                        if (d.isFile()) {
                            dists.createPathElement().setLocation(d);
                        }
                    }

                    Path srcPath = new Path(project);
                    srcPath.setLocation(f);
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
     * When user set relative file path - we don't know if on the slave is the same
     * file system. So we need to check it and correct it if necessary.
     *
     * @param node node where for which we want to normalize it
     * @param path rel. path
     * @return normalized path
     * @throws IOException
     * @throws Exception
     */
    private String normalizeRelPathForNode(Node node, String path) throws IOException, Exception {
        String separator = getFileSeparatorForNode(node);
        return path.replace("/", separator);
    }

    /**
     * Gets file separator of the given node. Taken from System.getProperty("file.separator")
     * on the node
     * @param node which node
     * @return separotor "/" or "\\"
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
     * Copies dist. directories or files to the "dist" directory in the project workspace.
     *
     * @param build build
     * @return true if all went well
     * @throws IOException
     * @throws InterruptedException
     * @throws Exception
     */
    private boolean copyDists(AbstractBuild build) throws IOException, InterruptedException, Exception {
        for (DistLocations dist : getDistLocations()) {
            if (dist.getDistDir() != null && !"".equals(dist.getDistDir())) {
                FilePath distDirFilePath = getDistDirFilePath(dist.getDistDir());
                if (distDirFilePath == null) {
                    if (!new FilePath(build.getWorkspace(),
                            normalizeRelPathForNode(build.getBuiltOn(), dist.getDistDir())).exists()) {
                        listener.getLogger().println(dist.getDistDir() + " :wasn't recognized as a valid "
                                + "absolute path on master or a relative path in the project workspace on +"
                                + "slave " + build.getBuiltOnStr());
                    }
                    //don't copy
                    continue;
                }
                listener.getLogger().print("Copying the distribution directory from "
                        + distDirFilePath.getRemote() + " to slave " + build.getBuiltOn()
                        + " to " + build.getWorkspace().getRemote() + ": ");
                distDirFilePath.copyRecursiveTo(build.getWorkspace().child("dist"));
                listener.getLogger().println("finished");
            }
        }
        return true;
    }

    /**
     * Locks executors of the nodes. Enter synchronized section controlled by the monitor
     * of the node. After it no jobs can be executed on those nodes exept mine.
     *
     * @param nodesList list of nodes which should be locked
     * @param build build
     * @param launcher launcher of this build
     * @return
     */
    private boolean entrySynchronizedSectionForNode(LinkedList<Node> nodesList,
            AbstractBuild build, Launcher launcher) {
        synchronized (nodesList.poll().toComputer()) {
            if (nodesList.isEmpty()) {
                return perform2(build, launcher);
            } else {
                return entrySynchronizedSectionForNode(nodesList, build, launcher);
            }
        }
    }

    /**
     * If wait for nodes/executors was checked then wait for all executors
     * on all nodes which are online and busy.
     * @param label Label which was set for this project/build
     * @throws InterruptedException
     */
    private void waitForNodes(Label label) throws InterruptedException {
        while (waitForNodes && (label.getIdleExecutors() + 1 < label.getTotalExecutors())) {
            listener.getLogger().println("Waiting for all other slaves in label " + label);
            Thread.sleep(1000);
        }
    }

    /**
     * Used to create a new distFork task for locking executor.
     *
     * @param l label of the node to which this task will be sent
     * @return new task
     */
    private DistForkTask createTask(Label l) {
        final int[] exitCode = new int[]{-1};
        String name = "Lock For This Executor (on label " + l.getName()
                + ". During and after this job no one can access this executor until it's released.";
        long duration = 100;
        DistForkTask t = new DistForkTask(l, name, duration, new Runnable() {

            public void run() {
                try {
                    Computer c = Computer.currentComputer();
                    Node n = c.getNode();
                    FilePath workDir = n.getRootPath().createTempDir("distfork", null);

                    try {
                        Launcher launcher = n.createLauncher(listener);
                        exitCode[0] = launcher.launch().cmds(new String[]{""}).stdout(listener).join();
                    } finally {
                        workDir.deleteRecursive();
                    }
                } catch (InterruptedException e) {
                    exitCode[0] = -1;
                } catch (Exception e) {
                    exitCode[0] = -1;
                }
            }
        });
        return t;
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
