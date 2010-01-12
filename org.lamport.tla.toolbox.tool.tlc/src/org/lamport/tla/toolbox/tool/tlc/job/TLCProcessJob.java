package org.lamport.tla.toolbox.tool.tlc.job;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.launching.IVMRunner;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMRunnerConfiguration;
import org.lamport.tla.toolbox.tool.ToolboxHandle;
import org.lamport.tla.toolbox.tool.tlc.TLCActivator;
import org.lamport.tla.toolbox.tool.tlc.launch.TraceExplorerDelegate;
import org.lamport.tla.toolbox.tool.tlc.output.IProcessOutputSink;
import org.lamport.tla.toolbox.tool.tlc.output.internal.BroadcastStreamListener;
import org.lamport.tla.toolbox.util.ResourceHelper;

import tlc2.TLC;

/**
 * A job to launch TLC as a separate process
 * @author Simon Zambrovski
 * @version $Id$
 */
public class TLCProcessJob extends TLCJob
{
    private IProcess process = null;
    private BroadcastStreamListener listener = null;

    /**
     * The tlc start time in milliseconds as given
     * by {@link System#currentTimeMillis()}.
     */
    private long tlcStartTime;
    /**
     * The tlc end time in milliseconds as given
     * by {@link System#currentTimeMillis()}.
     */
    private long tlcEndTime;

    /**
     * Constructs a process job
     * @param name
     */
    public TLCProcessJob(String specName, String modelName, ILaunch launch)
    {
        super(specName, modelName, launch);
    }

    /* (non-Javadoc)
     * @see org.eclipse.core.runtime.jobs.Job#run(org.eclipse.core.runtime.IProgressMonitor)
     */
    protected IStatus run(IProgressMonitor monitor)
    {
        try
        {

            // start the monitor ( we don't know the )
            monitor.beginTask("Running TLC model checker", IProgressMonitor.UNKNOWN);

            // step 1
            monitor.worked(STEP);
            monitor.subTask("Preparing the TLC Launch");

            // classpath
            String[] classPath = new String[] { ToolboxHandle.getTLAToolsClasspath().toOSString() };

            // full-qualified class name of the main class
            String mainClassFQCN = TLC.class.getName();
            // String mainClassFQCN = SANY.class.getName();

            // arguments
            String[] arguments = constructProgramArguments();

            System.out.println("---------------------------");
            System.out.println("TLC ARGUMENTS:");
            System.out.println("---------------------------");
            for (int i = 0; i < arguments.length; i++)
            {
                System.out.println(arguments[i]);
            }
            System.out.println("---------------------------");
            System.out.println("END TLC ARGUMENTS");
            System.out.println("---------------------------");

            // get max heap size
            // the default value should never be returned if validation
            // of the main model page is correct
            int maxHeapSize = launch.getLaunchConfiguration().getAttribute(LAUNCH_MAX_HEAP_SIZE, 500);

            // using -D to pass the System property of the location of standard modules
            String[] vmArgs = new String[] { "-DTLA-Library=" + ToolboxHandle.getModulesClasspath().toOSString(),
                    "-Xmx" + maxHeapSize + "m" };

            // assemble the config
            VMRunnerConfiguration tlcConfig = new VMRunnerConfiguration(mainClassFQCN, classPath);
            // tlcConfig.setProgramArguments(new String[] { ResourceHelper.getModuleName(rootModule) });
            tlcConfig.setProgramArguments(arguments);
            tlcConfig.setVMArguments(vmArgs);
            tlcConfig.setWorkingDirectory(ResourceHelper.getParentDirName(rootModule));

            // get default VM (the same the toolbox is started with)
            IVMRunner runner = JavaRuntime.getDefaultVMInstall().getVMRunner(ILaunchManager.RUN_MODE);

            launch.setAttribute(DebugPlugin.ATTR_CAPTURE_OUTPUT, "true");

            // step 2
            monitor.worked(STEP);
            monitor.subTask("Launching TLC");

            try
            {
                // step 3
                runner.run(tlcConfig, launch, new SubProgressMonitor(monitor, STEP));
                tlcStartTime = System.currentTimeMillis();
            } catch (CoreException e)
            {
                return new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID, "Error launching TLC modle checker", e);
            }

            // find the running process
            this.process = findProcessForLaunch(launch);

            // step 4
            monitor.worked(STEP);
            monitor.subTask("Connecting to running instance");

            // process found
            if (this.process != null)
            {
                // step 5
                monitor.worked(STEP);
                monitor.subTask("Model checking...");

                // register the broadcasting listener

                String modelFileName = launch.getLaunchConfiguration().getFile().getName();

                /*
                 * If TLC is being run for model checking then the stream kind passed to
                 * BroadcastStreamListener (second argument) is IProcessOutputSink.TYPE_OUT.
                 * If TLC is being run for trace exploration, then it is
                 * IProcessOutputSink.TYPE_TRACE_EXPLORE.
                 */

                int kind = IProcessOutputSink.TYPE_OUT;

                if (launch.getLaunchMode().equals(TraceExplorerDelegate.MODE_TRACE_EXPLORE))
                {
                    kind = IProcessOutputSink.TYPE_TRACE_EXPLORE;
                }

                listener = new BroadcastStreamListener(modelFileName, kind);

                process.getStreamsProxy().getOutputStreamMonitor().addListener(listener);
                process.getStreamsProxy().getErrorStreamMonitor().addListener(listener);

                // loop until the process is terminated
                while (checkAndSleep())
                {
                    // check the cancellation status
                    if (monitor.isCanceled())
                    {
                        // cancel the TLC
                        try
                        {
                            process.terminate();
                        } catch (DebugException e)
                        {
                            // react on the status code
                            switch (e.getStatus().getCode()) {
                            case DebugException.TARGET_REQUEST_FAILED:
                            case DebugException.NOT_SUPPORTED:
                            default:
                                return new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID,
                                        "Error terminating the running TLC instance. This is a bug. Make sure to exit the toolbox.");
                            }
                        }

                        // abnormal termination
                        tlcEndTime = System.currentTimeMillis();
                        return Status.CANCEL_STATUS;
                    }
                }

                // step 6
                monitor.worked(STEP);
                monitor.subTask("Model checking finished.");

                // handle finish
                doFinish();

                tlcEndTime = System.currentTimeMillis();

                return Status.OK_STATUS;

            } else
            {
                // process not found
                return new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID,
                        "Error launching TLC, the launched process cound not be found");
            }

        } catch (CoreException e)
        {
            return new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID, "Error reading model parameters", e);
        } finally
        {
            // send the notification about completion
            if (listener != null)
            {
                listener.streamClosed();
            }
            // make sure to complete the monitor
            monitor.done();
        }
    }

    /**
     * @see {@link TLCJob#checkAndSleep()}
     */
    public boolean checkAndSleep()
    {
        try
        {
            // go sleep
            Thread.sleep(TIMEOUT);

        } catch (InterruptedException e)
        {
            // nothing to do
            // e.printStackTrace();
        }
        // return true if the TLC is still calculating
        return (!process.isTerminated());
    }

    /**
     * Retrieves a process to a given ILaunch
     * @param launch
     * @return
     */
    public static IProcess findProcessForLaunch(ILaunch launch)
    {
        // find the process
        IProcess[] processes = DebugPlugin.getDefault().getLaunchManager().getProcesses();
        for (int i = 0; i < processes.length; i++)
        {
            if (processes[i].getLaunch().equals(launch))
            {
                return processes[i];
            }
        }
        return null;
    }

    /**
     * Returns the tlc start time in milliseconds as given
     * by {@link System#currentTimeMillis()}.
     * @return
     */
    public long getTlcStartTime()
    {
        return tlcStartTime;
    }

    /**
     * Returns the tlc end time in milliseconds as given
     * by {@link System#currentTimeMillis()}.
     * @return
     */
    public long getTlcEndTime()
    {
        return tlcEndTime;
    }

}
