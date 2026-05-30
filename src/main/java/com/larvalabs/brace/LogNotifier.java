package com.larvalabs.brace;

import java.util.LinkedHashMap;

/** Always-on notifier: emits a structured {@code regression} log event. */
public class LogNotifier implements Notifier {

    @Override
    public void notifyRegression(RegressionTracker.Regression r) {
        var data = new LinkedHashMap<String, Object>();
        data.put("type", r.type());
        data.put("route", r.route());
        data.put("message", r.message());
        data.put("firstSeen", r.firstSeen().toString());
        Log.event("regression", data);
    }
}
