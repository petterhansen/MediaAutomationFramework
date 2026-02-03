package com.plugins.telegram.internal;

import com.framework.core.Kernel;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Portierung von src/processing/TelegramWizard.java
 * Führt den User interaktiv durch den Download-Prozess.
 */
public class TelegramWizard {
    private static final Logger logger = LoggerFactory.getLogger(TelegramWizard.class);

    private enum Step {
        AMOUNT, QUERY, MODE, FORCE
    }

    private static class WizardState {
        Step step;
        int amount;
        String query;
        String mode;
        long lastBotMessageId = 0;
        Integer threadId;
    }

    private final Map<Long, WizardState> activeStates = new HashMap<>();
    private final Kernel kernel;
    private final Consumer<TelegramMessage> messageSender; // Callback zum Senden

    public record TelegramMessage(long chatId, Integer threadId, String text, boolean html) {
    }

    public TelegramWizard(Kernel kernel, Consumer<TelegramMessage> messageSender) {
        this.kernel = kernel;
        this.messageSender = messageSender;
    }

    public boolean isActive(long chatId) {
        return activeStates.containsKey(chatId);
    }

    public void startDlWizard(long chatId, Integer threadId) {
        WizardState state = new WizardState();
        state.step = Step.AMOUNT;
        state.threadId = threadId;
        activeStates.put(chatId, state);
        ask(chatId, threadId,
                "📥 <b>Download Wizard</b>\n\nWie viele Dateien/Posts sollen geladen werden?\n<i>(Bitte eine Zahl eingeben, z.B. 10)</i>");
    }

    public void startDlWizard(long chatId) {
        startDlWizard(chatId, null);
    }

    private void ask(long chatId, Integer threadId, String text) {
        messageSender.accept(new TelegramMessage(chatId, threadId, text, true));
    }

    public void cancel(long chatId) {
        WizardState state = activeStates.remove(chatId);
        Integer threadId = (state != null) ? state.threadId : null;
        ask(chatId, threadId, "🚫 <b>Abbruch.</b>");
    }

    public void handleInput(long chatId, String text) {
        WizardState state = activeStates.get(chatId);
        if (state == null)
            return;

        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("/cancel")) {
            cancel(chatId);
            return;
        }

        try {
            switch (state.step) {
                case AMOUNT -> {
                    try {
                        int amt = Integer.parseInt(text.trim());
                        if (amt < 1)
                            throw new NumberFormatException();
                        state.amount = amt;
                        state.step = Step.QUERY;
                        ask(chatId, state.threadId,
                                "🔍 <b>Suchbegriff</b>\n\nGib den Namen, eine URL oder Tags ein:\nBeispiel: <code>mario</code> oder <code>r34:zelda</code>");
                    } catch (NumberFormatException e) {
                        ask(chatId, state.threadId, "⚠️ <b>Fehler:</b> Bitte eine gültige Zahl eingeben!");
                    }
                }
                case QUERY -> {
                    state.query = text.trim();
                    state.step = Step.MODE;
                    ask(chatId, state.threadId,
                            "⚙️ <b>Modus</b>\n\nWas soll geladen werden?\nAntworte mit: <code>post</code>, <code>img</code>, <code>vid</code> oder <code>all</code>");
                }
                case MODE -> {
                    String m = text.trim().toLowerCase();
                    if (m.equals("bilder") || m.equals("bild"))
                        m = "img";
                    if (m.equals("video") || m.equals("videos"))
                        m = "vid";
                    if (!m.equals("post") && !m.equals("img") && !m.equals("vid") && !m.equals("all")) {
                        ask(chatId, state.threadId,
                                "⚠️ Unbekannter Modus. Bitte <code>post</code>, <code>img</code> oder <code>vid</code> wählen.");
                        return;
                    }
                    state.mode = m;
                    state.step = Step.FORCE;
                    ask(chatId, state.threadId,
                            "💪 <b>Force Download?</b>\n\nSoll die History ignoriert werden?\nAntworte mit: <code>ja</code> oder <code>nein</code>");
                }
                case FORCE -> {
                    boolean force = text.equalsIgnoreCase("ja") || text.equalsIgnoreCase("y")
                            || text.equalsIgnoreCase("yes");

                    // Task erstellen und an Kernel übergeben
                    QueueTask task = new QueueTask("SEARCH_BATCH");
                    task.addParameter("query", state.query);
                    task.addParameter("amount", state.amount);
                    task.addParameter("mode", state.mode);
                    task.addParameter("ignoreHistory", force);
                    task.addParameter("initiatorChatId", String.valueOf(chatId));
                    if (state.threadId != null) {
                        task.addParameter("initiatorThreadId", String.valueOf(state.threadId));
                    }

                    // Source erkennen (einfache Heuristik)
                    if (state.query.contains("r34:"))
                        task.addParameter("source", "r34");
                    else
                        task.addParameter("source", "party"); // Default

                    kernel.getQueueManager().addTask(task);

                    ask(chatId, state.threadId, "✅ <b>Task gestartet:</b> " + state.query + " (" + state.amount + ")");
                    activeStates.remove(chatId);
                }
            }
        } catch (Exception e) {
            logger.error("Wizard Fehler", e);
            WizardState exState = activeStates.remove(chatId);
            Integer threadId = (exState != null) ? exState.threadId : null;
            ask(chatId, threadId, "💥 <b>Interner Fehler.</b> Wizard beendet.");
        }
    }
}