package org.jenkinsci.plugins.beakerbuilder;

import com.github.vjuranek.beaker4j.remote_model.BeakerTask;
import com.github.vjuranek.beaker4j.remote_model.TaskStatus;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import org.apache.xmlrpc.XmlRpcException;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Timer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author ogondza.
 */
public class TaskWatchdogTest {

    @Test
    public void trivia() throws Exception {
        BeakerTask task = mock(BeakerTask.class);
        TaskWatchdog tw = new TaskWatchdog(task, TaskStatus.ABORTED);

        assertEquals(false, tw.isFinished());
        assertEquals(TaskStatus.ABORTED, tw.getStatus());
        assertEquals(TaskStatus.ABORTED, tw.getOldStatus());
    }

    /**
     * Failing to parse Beaker task info must not kill the Timer.
     */
    @Test
    public void failGettingData() throws Exception {
        BeakerTask task = mock(BeakerTask.class);
        final TaskWatchdog tw = new TaskWatchdog(task, TaskStatus.NEW);

        when(task.getInfo())
                .thenThrow(new XmlRpcException("FAKE")) // Fail on first attempt
                .thenReturn(getDummyTaskInfo())
        ;

        class WaitThread extends Thread {
            private volatile Boolean resumed = null;
            @Override public void run() {
                synchronized (tw) {
                    try {
                        resumed = false;
                        tw.wait();
                        resumed = true;
                    } catch (InterruptedException e) {
                        System.err.println("Watcher not notified");
                        e.printStackTrace();
                    }
                }
            }
        }
        WaitThread thread = new WaitThread();
        thread.start();

        Thread.sleep(10);

        assertFalse(thread.resumed);

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(tw, 0, 1);

        Thread.sleep(50);

        if (!thread.resumed) {
            fail(String.format("Client not resumed%n" + Joiner.on("%n").join(Arrays.asList(thread.getStackTrace()))));
        }

        assertEquals(true, tw.isFinished());
        assertEquals(TaskStatus.COMPLETED, tw.getStatus());
        assertEquals(TaskStatus.NEW, tw.getOldStatus());
    }

    /*package*/ static BeakerTask.TaskInfo getDummyTaskInfo() {
        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put("id", "42");
        data.put("method", "???");
        data.put("is_failed", false);
        data.put("is_finished", true);
        data.put("state_label", "???");
        data.put("state", "completed");
        data.put("result", "pass");
        BeakerTask.TaskInfo taskInfo = new BeakerTask.TaskInfo(data);
        taskInfo.setWorker(new BeakerTask.Worker("name"));
        return taskInfo;
    }
}
