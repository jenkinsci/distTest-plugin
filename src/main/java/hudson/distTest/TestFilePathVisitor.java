/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package hudson.distTest;

import hudson.FilePath;
import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *
 * @author Oneill
 */
class TestFilePathVisitor {

    private ConcurrentLinkedQueue<String> listOfTests = new ConcurrentLinkedQueue<String>();
    void visit(String relativePath) throws IOException {
        getListOfTests().add(relativePath);
    }

    /**
     * @return the listOfTests
     */
    public ConcurrentLinkedQueue<String> getListOfTests() {
        return listOfTests;
    }

}
