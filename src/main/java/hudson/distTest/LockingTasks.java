/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package hudson.distTest;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.tasks.Builder;
import java.util.ArrayList;

/**
 *
 * @author mnovak
 */
public class LockingTasks extends Builder {

    private ArrayList<java.util.concurrent.Future<FreeStyleBuild>> futList = null;
    private ArrayList<FreeStyleProject> fspList = null;
    private ArrayList<Node> nodeList = null;

   public LockingTasks(ArrayList<java.util.concurrent.Future<FreeStyleBuild>> futList, ArrayList<FreeStyleProject> fspList
           , ArrayList<Node> nodeList)
   {

       this.futList = futList;

       this.fspList = fspList;

       this.nodeList = nodeList;

   }

    /**
     * @return the futList
     */
    public ArrayList<java.util.concurrent.Future<FreeStyleBuild>> getFutList() {
        return futList;
    }

    /**
     * @param futList the futList to set
     */
    public void setFutList(ArrayList<java.util.concurrent.Future<FreeStyleBuild>> futList) {
        this.futList = futList;
    }

    /**
     * @return the fspList
     */
    public ArrayList<FreeStyleProject> getFspList() {
        return fspList;
    }

    /**
     * @param fspList the fspList to set
     */
    public void setFspList(ArrayList<FreeStyleProject> fspList) {
        this.fspList = fspList;
    }

    /**
     * @return the nodeList
     */
    public ArrayList<Node> getNodeList() {
        return nodeList;
    }

    /**
     * @param nodeList the nodeList to set
     */
    public void setNodeList(ArrayList<Node> nodeList) {
        this.nodeList = nodeList;
    }
}
