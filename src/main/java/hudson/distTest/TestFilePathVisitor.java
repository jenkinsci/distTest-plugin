/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package hudson.distTest;

import hudson.FilePath;
import java.io.IOException;
import java.util.LinkedList;

/**
 *
 * @author Oneill
 */
class TestFilePathVisitor {

    private LinkedList<String> listOfTests = new LinkedList<String>();
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
