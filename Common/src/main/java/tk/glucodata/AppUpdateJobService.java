package tk.glucodata;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;

/** Network-constrained, persisted background downloads; installation waits for the Home screen. */
public final class AppUpdateJobService extends JobService {
    private final Map<Integer, AtomicBoolean> tasks = new HashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        AtomicBoolean cancelled = new AtomicBoolean();
        tasks.put(params.getJobId(), cancelled);
        AppUpdates.check(this, false, () -> {
            if (tasks.get(params.getJobId()) == cancelled) {
                tasks.remove(params.getJobId());
                jobFinished(params, "retry".equals(AppUpdates.prefs(this).getString("state", "")));
            }
        }, cancelled);
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        AtomicBoolean cancelled = tasks.remove(params.getJobId());
        if (cancelled != null) cancelled.set(true);
        return true;
    }
}

