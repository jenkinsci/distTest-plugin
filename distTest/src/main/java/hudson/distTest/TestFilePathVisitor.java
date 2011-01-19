package hudson.distTest;

import hudson.FilePath;
import java.io.IOException;
import java.util.LinkedList;

/**
 *
 * Class which visit all the test classes.
 *
 *  @author Miroslav Novak
 */
class TestFilePathVisitor {

    private LinkedList<String> listOfTests = new LinkedList<String>();
   
     /**
     * if it is a test class then put it to a list of tests.
     *
     * @param f root file path
     * @param relativePath relative path
     * @throws IOException
     */
    void visit(String relativePath) throws IOException {
        getListOfTests().add(relativePath);
    }

    /**
     * @return the listOfTests
     */
    public LinkedList<String> getListOfTests() {
        return listOfTests;
    }

}
