package org.symphonykernel.demo;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.symphonykernel.UserSession;
import org.symphonykernel.UserSessionStepDetails;
import org.symphonykernel.core.IUserSessionBase;

@Component
class DemoSessions implements IUserSessionBase {
    private static final int MAX_REQUESTS = 256;
    private final Map<String, UserSession> sessions = new LinkedHashMap<>();
    private final Map<String, Map<String, UserSessionStepDetails>> details = new LinkedHashMap<>();

    @Override
    public synchronized List<UserSession> getSession(String id) {
        return sessions.values().stream().filter(session -> Objects.equals(id, session.getSessionID())).toList();
    }

    @Override
    public synchronized UserSession save(UserSession session) {
        sessions.put(session.getRequestId(), session);
        if (sessions.size() > MAX_REQUESTS) {
            String oldest = sessions.keySet().iterator().next();
            sessions.remove(oldest);
            details.remove(oldest);
        }
        return session;
    }

    @Override public synchronized UserSession findById(String id) { return sessions.get(id); }

    @Override
    public synchronized String getLastRequestId(String sessionId) {
        List<UserSession> history = getSession(sessionId);
        return history.isEmpty() ? null : history.get(history.size() - 1).getRequestId();
    }

    @Override
    public synchronized List<UserSessionStepDetails> getRequestDetails(String id) {
        return List.copyOf(details.getOrDefault(id, Map.of()).values());
    }

    @Override
    public synchronized UserSessionStepDetails getRequestDetails(String id, String stepName) {
        return details.getOrDefault(id, Map.of()).get(stepName);
    }

    @Override
    public synchronized void saveRequestDetails(String id, String stepName, String data) {
        if (!sessions.containsKey(id) || stepName == null) return;
        var step = new UserSessionStepDetails();
        step.setRequestId(id);
        step.setStepName(stepName);
        step.setData(data);
        step.setCreateDt(new Date());
        details.computeIfAbsent(id, key -> new LinkedHashMap<>()).put(stepName, step);
    }

    @Override public UserSessionStepDetails getFollowUpDetails(String id) { return null; }
}