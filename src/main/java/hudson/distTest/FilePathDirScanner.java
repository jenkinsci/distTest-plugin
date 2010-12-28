/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.distTest;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import java.io.Serializable;
import java.io.IOException;

/**
 *
 * @author Oneill
 */
public class FilePathDirScanner implements Serializable {

    private final long serialVersionUID = 1L;
    
    public FilePathDirScanner()   {
    }

    void scan(FilePath f, String path, TestFilePathVisitor visitor) throws IOException {
        try {
            // if it's a directory then scan its contents
            if (f.isDirectory()) {
                for (FilePath child : f.list()) {
                    scan(child, path + child.getBaseName() + "/", visitor);
                }
                // if it's a file and end with .class suffix and contains *test* string then visit (add to test queue)
            } else if ((f.getName().endsWith(".class")) && (f.getBaseName().toLowerCase().contains("test")) &&
                    (!f.getName().contains("$"))) {
                visitor.visit(path.substring(0, path.length() - 1).replace("/", "."));
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }

    }

    void scan(FilePath dir, TestFilePathVisitor visitor) throws IOException {
        scan(dir, "", visitor);
    }
}


