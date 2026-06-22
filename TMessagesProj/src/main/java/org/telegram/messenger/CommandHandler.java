package org.telegram.messenger;

import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import android.widget.Toast;
import android.content.Context;
import android.os.Looper;
import org.telegram.messenger.AiManager;
import org.telegram.tgnet.ConnectionsManager;

public class CommandHandler {

    private static boolean invisibleMode = false;
    private static boolean autoReplyEnabled = false;
    private static String autoReplyMessage = null;
    private static boolean waitingForAutoReply = false;

    // /ai user feature
    private static final HashSet<Long> aiUserChats = new HashSet<>();
    private static final HashMap<Long, Long> aiUserCooldown = new HashMap<>();
    private static final HashMap<Long, java.util.LinkedList<String>> groupMessageCache = new HashMap<>();
    private static volatile String lastAiError = null;
    private static final int GROUP_CACHE_SIZE = 60;
    private static final HashMap<Long, LinkedList<Integer>> myMessageIdsCache = new HashMap<>();
    private static final int MAX_CACHED_MESSAGE_IDS = 100;
    private static int aiResponseCount = 0;
    private static boolean adEnabled = false;
    private static final String AD_TEXT = "\n\n💡 API для GPT, Claude, DeepSeek и других моделей: @imbekapi_bot";
    private static final int AD_EVERY_N = 5;

    // Event log buffer
    private static final LinkedList<String> eventLog = new LinkedList<>();
    private static final int MAX_LOG_ENTRIES = 30;
    public static void addLog(String entry) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        synchronized (eventLog) {
            eventLog.addLast("[" + ts + "] " + entry);
            if (eventLog.size() > MAX_LOG_ENTRIES) eventLog.removeFirst();
        }
    }
    private static final long AI_USER_COOLDOWN_MS = 10000;

    // AI wizard state
    public static final int AI_WIZARD_NONE = 0;
    public static final int AI_WIZARD_URL = 1;
    public static final int AI_WIZARD_MODEL = 2;
    public static final int AI_WIZARD_TOKEN = 3;
    public static final int AI_WIZARD_VISION_URL = 4;
    public static final int AI_WIZARD_VISION_MODEL = 5;
    public static final int AI_WIZARD_VISION_TOKEN = 6;
    private static boolean visionEnabled = false;
    private static int aiWizardStep = AI_WIZARD_NONE;
    private static String aiWizardUrl = "";
    private static String aiWizardModel = "";
    private static long aiWizardDialogId = 0;

    public static boolean handle(String text, long dialogId, MessageObject replyToMsg) {
        try {
            java.io.FileWriter dbg = new java.io.FileWriter("/sdcard/ai_debug.txt", true);
            dbg.write("handle() called, text=" + text + "\n");
            dbg.close();
        } catch (Exception ignored) {}
        ensureMyMessageObserverRegistered();
        if (text == null || !text.startsWith("/")) return false;

        String[] parts = text.trim().split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/id":
                handleId(dialogId);
                return true;
            case "/ping":
                handlePing(dialogId);
                return true;
            case "/calc":
                handleCalc(arg, dialogId);
                return true;
            case "/dice":
                handleDice(dialogId);
                return true;
            case "/coin":
                handleCoin(dialogId);
                return true;
            case "/8ball":
                handleEightBall(dialogId);
                return true;
            case "/invisible":
                handleInvisible(dialogId);
                return true;
            case "/help":
                handleHelp(dialogId);
                return true;
            case "/log":
                handleLog(dialogId);
                return true;
            case "/tts":
                handleTts(arg, dialogId, replyToMsg);
                return true;
            case "/ghostping":
                return true;
            case "/autoreply":
                handleAutoReply(arg, dialogId);
                return true;
            case "/remind":
                handleRemind(arg, dialogId);
                return true;
            case "/ai":
                handleAi(arg, dialogId, replyToMsg);
                return true;
            case "/ad":
                adEnabled = !adEnabled;
                final boolean adNowOn = adEnabled;
                AndroidUtilities.runOnUIThread(() ->
                    Toast.makeText(ApplicationLoader.applicationContext, adNowOn ? "📢 Реклама включена" : "🔇 Реклама выключена", Toast.LENGTH_SHORT).show());
                return true;
            case "/exit":
                handleExit(dialogId);
                return true;
            case "/qr":
            case "/chatstat":
            case "/topwords":
            case "/activity":
            case "/weather":
                return true;
            default:
                return false;
        }
    }

    private static void sendLocal(long dialogId, String text) {
        sendLocal(dialogId, text, null);
    }

    private static void sendLocal(long dialogId, String text, MessageObject replyToMsg) {
        AndroidUtilities.runOnUIThread(() -> {
            CharSequence[] msg = {text};
            java.util.ArrayList<org.telegram.tgnet.TLRPC.MessageEntity> entities =
                org.telegram.messenger.MediaDataController.getInstance(UserConfig.selectedAccount).getEntities(msg, true);
            String cleanText = msg[0].toString();
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(cleanText, dialogId, replyToMsg, null, null, false, entities, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(params);
        });
    }

    private static long ttsCooldown = 0;

    private static void handleTts(String arg, long dialogId, MessageObject replyToMsg) {
        long now = System.currentTimeMillis();
        if (now - ttsCooldown < 10000) return;
        ttsCooldown = now;

        String text = arg.trim();
        if (text.isEmpty() && replyToMsg != null && replyToMsg.messageOwner != null) {
            text = replyToMsg.messageOwner.message;
        }
        if (text == null || text.isEmpty()) {
            sendLocal(dialogId, "❌ Формат: /tts [текст] или реплай на сообщение");
            return;
        }
        final String finalText = text;
        AiManager.textToSpeech(finalText, new AiManager.TtsCallback() {
            @Override
            public void onResult(java.io.File file) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        org.telegram.tgnet.TLRPC.TL_document doc = new org.telegram.tgnet.TLRPC.TL_document();
                        doc.mime_type = "audio/mpeg";
                        doc.file_name = "tts.mp3";
                        org.telegram.tgnet.TLRPC.TL_documentAttributeAudio audio = new org.telegram.tgnet.TLRPC.TL_documentAttributeAudio();
                        audio.voice = true;
                        doc.attributes.add(audio);
                        SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(doc, null, file.getAbsolutePath(), dialogId, replyToMsg, null, null, null, null, null, true, 0, 0, 0, null, null, false);
                        SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(p);
                    } catch (Exception e) {
                        addLog("❌ TTS отправка: " + e.getMessage());
                    }
                });
            }
            @Override
            public void onError(String error) {
                addLog("❌ TTS: " + error);
                sendLocal(dialogId, "❌ " + error);
            }
        });
    }

    public static void sendAiResult(long dialogId, String resultRaw, MessageObject replyToMsg, int account) {
        String result = resultRaw;
        aiResponseCount++;
        if (adEnabled && aiResponseCount % AD_EVERY_N == 0) {
            result = result + AD_TEXT;
        }
        java.util.regex.Matcher rm = java.util.regex.Pattern.compile("\\[REACTION:([^\\]]+)\\]").matcher(result);
        if (rm.find()) {
            String emojis = rm.group(1).trim();
            java.util.List<String> emojiList = new java.util.ArrayList<>();
            java.util.List<Integer> cps = new java.util.ArrayList<>();
            emojis.codePoints().forEach(cps::add);
            StringBuilder cur = new StringBuilder();
            for (int cp : cps) {
                if (Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.EMOTICONS ||
                    Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS ||
                    cp >= 0x1F000) {
                    if (cur.length() > 0) { emojiList.add(cur.toString()); cur = new StringBuilder(); }
                    cur.appendCodePoint(cp);
                } else {
                    cur.appendCodePoint(cp);
                }
            }
            if (cur.length() > 0) emojiList.add(cur.toString());
            if (!emojiList.isEmpty()) {
                String chosen = emojiList.get((int)(Math.random() * emojiList.size())).trim();
                if (!chosen.isEmpty() && replyToMsg != null) {
                    sendReactionDirect(dialogId, replyToMsg.getId(), chosen);
                }
            }
            result = rm.replaceAll("").trim();
        }
        // Проверяем есть ли блок кода
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("```(python|py|markdown|md)?\\s*\\n([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(result);
        if (m.find()) {
            String lang = m.group(1) != null ? m.group(1).toLowerCase() : "";
            String code = m.group(2);
            String ext = (lang.equals("python") || lang.equals("py")) ? ".py" : ".md";
            String textBefore = result.substring(0, m.start()).trim();
            String textAfter = result.substring(m.end()).trim();
            String caption = (textBefore + " " + textAfter).trim();
            try {
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Telegram/ai_files");
                dir.mkdirs();
                java.io.File file = new java.io.File(dir, "ai_" + System.currentTimeMillis() + ext);
                try (java.io.FileWriter fw = new java.io.FileWriter(file)) { fw.write(code); }
                final String finalCaption = caption.isEmpty() ? null : caption;
                final java.io.File finalFile = file;
                final String finalExt = ext;
                final String resultForLambda = result;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        org.telegram.tgnet.TLRPC.TL_document doc = new org.telegram.tgnet.TLRPC.TL_document();
                        doc.mime_type = finalExt.equals(".py") ? "text/x-python" : "text/markdown";
                        org.telegram.tgnet.TLRPC.TL_documentAttributeFilename attr = new org.telegram.tgnet.TLRPC.TL_documentAttributeFilename();
                        attr.file_name = "code" + finalExt;
                        doc.attributes.add(attr);
                        SendMessagesHelper.SendMessageParams fp = SendMessagesHelper.SendMessageParams.of(doc, null, finalFile.getAbsolutePath(), dialogId, replyToMsg, null, finalCaption, null, null, null, true, 0, 0, 0, null, null, false);
                        SendMessagesHelper.getInstance(account).sendMessage(fp);
                    } catch (Exception e) {
                        addLog("❌ Файл отправка: " + e.getMessage());
                        sendAiResult(dialogId, resultForLambda, replyToMsg, UserConfig.selectedAccount);
                    }
                });
                return;
            } catch (Exception e) {
                addLog("❌ Файл создание: " + e.getMessage());
            }
        }
        // Обычный текст
        final String finalResult = result;
        AndroidUtilities.runOnUIThread(() -> {
            CharSequence[] msg = {finalResult};
            java.util.ArrayList<org.telegram.tgnet.TLRPC.MessageEntity> entities =
                org.telegram.messenger.MediaDataController.getInstance(UserConfig.selectedAccount).getEntities(msg, true);
            String cleanText = msg[0].toString();
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(cleanText, dialogId, replyToMsg, null, null, false, entities, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(account).sendMessage(params);
        });
    }

    private static void handleId(long dialogId) {
        long myId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        sendLocal(dialogId, "🆔 Твой ID: " + myId);
    }

    private static void handlePing(long dialogId) {
        long start = System.currentTimeMillis();
        long ping = System.currentTimeMillis() - start;
        sendLocal(dialogId, "🏓 Пинг: " + ping + "ms");
    }

    private static void handleCalc(String expr, long dialogId) {
        try {
            double result = eval(expr);
            sendLocal(dialogId, result == (long)result ? String.valueOf((long)result) : String.valueOf(result));
        } catch (Exception e) {
            sendLocal(dialogId, "❌ Ошибка в выражении");
        }
    }

    private static void handleDice(long dialogId) {
        int result = (int)(Math.random() * 6) + 1;
        sendLocal(dialogId, "🎲 Выпало: " + result);
    }

    private static void handleCoin(long dialogId) {
        sendLocal(dialogId, Math.random() < 0.5 ? "🪙 Орёл" : "🪙 Решка");
    }

    private static void handleEightBall(long dialogId) {
        String[] answers = {
            "Да", "Нет", "Возможно", "Определённо нет",
            "Скорее всего", "Спроси позже", "Без сомнений"
        };
        String ans = answers[(int)(Math.random() * answers.length)];
        sendLocal(dialogId, "🎱 " + ans);
    }

    private static void handleInvisible(long dialogId) {
        invisibleMode = !invisibleMode;
        if (invisibleMode) {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(true, false);
            MessagesController.getInstance(UserConfig.selectedAccount).forceOffline();
        } else {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(false, false);
        }
        Toast.makeText(ApplicationLoader.applicationContext, invisibleMode
            ? "👻 Режим невидимки включён"
            : "👁 Режим невидимки выключён", Toast.LENGTH_SHORT).show();
    }

    private static void handleLog(long dialogId) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛠 Состояние мода\n\n");
        sb.append("👻 Invisible: ").append(invisibleMode ? "ВКЛ" : "выкл").append("\n");
        sb.append("🤖 AutoReply: ").append(autoReplyEnabled ? "ВКЛ" : "выкл").append("\n");
        sb.append("⏳ Waiting for autoreply text: ").append(waitingForAutoReply).append("\n");
        sb.append("🧙 AI Wizard step: ").append(aiWizardStep).append("\n");
        sb.append("🤖 AI User enabled (this chat): ").append(aiUserChats.contains(dialogId)).append("\n");
        sb.append("🤖 AI User enabled chats count: ").append(aiUserChats.size()).append("\n");
        Long cd = aiUserCooldown.get(dialogId);
        if (cd != null) {
            long left = AI_USER_COOLDOWN_MS - (System.currentTimeMillis() - cd);
            sb.append("⏱ AI User cooldown left: ").append(Math.max(0, left)).append("ms\n");
        } else {
            sb.append("⏱ AI User cooldown: нет\n");
        }
        LinkedList<Integer> cached = myMessageIdsCache.get(dialogId);
        sb.append("📦 Cached my message IDs (this chat): ").append(cached != null ? cached.size() : 0).append("\n");
        sb.append("🤖 AI role: ").append(AiManager.getRoleName(AiManager.getCurrentRole(ApplicationLoader.applicationContext))).append("\n");
        java.util.LinkedList<String> gcache = groupMessageCache.get(dialogId);
        sb.append("💬 Group message cache (this chat): ").append(gcache != null ? gcache.size() : 0).append("/20\n");
        sb.append("❌ Last AI error: ").append(lastAiError != null ? lastAiError : "нет").append("\n");
        sb.append("\n📋 Лог событий:\n");
        synchronized (eventLog) {
            if (eventLog.isEmpty()) {
                sb.append("  (пусто)\n");
            } else {
                for (String entry : eventLog) {
                    sb.append("  ").append(entry).append("\n");
                }
            }
        }
        sendLocal(dialogId, sb.toString());
    }

    private static void handleHelp(long dialogId) {
        String help =
                "┌─────────────────┐\n" +
                "│  Квас · Help    │\n" +
                "└─────────────────┘\n" +
                "\n" +
                "👤 Утилиты\n" +
                "├ `/id` — твой ID\n" +
                "├ `/ping` — пинг\n" +
                "├ `/calc <выр>` — калькулятор\n" +
                "├ `/dice` · `/coin` · `/8ball`\n" +
                "└ `/remind <сек> <текст>` — напоминание\n" +
                "\n" +
                "👻 Режимы\n" +
                "├ `/invisible` — невидимка\n" +
                "└ `/autoreply` — автоответчик\n" +
                "\n" +
                "🤖 AI\n" +
                "├ `/ai <вопрос>` — спросить\n" +
                "├ `/ai role` — сменить роль\n" +
                "├ `/ai user` — автоответ в чате\n" +
                "├ `/ai user off` — выключить везде\n" +
                "├ `/ai clean` — очистить историю\n" +
                "└ `/ai clean mem` — очистить память\n" +
                "\n" +
                "📋 `/log` — события и диагностика\n" +
                "━━━━━━━━━━━━━━━━━";

        sendLocal(dialogId, help);
    }

    private static void handleGhostPing(String arg, long dialogId) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Формат: /ghostping <секунды> <сообщение>", Toast.LENGTH_SHORT).show());
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[0]);
            String msg = parts[1];
            int[] sentMsgId = {0};
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(
                SendMessagesHelper.SendMessageParams.of(msg, dialogId, null, null, null, false, null, null, null, false, 0, 0, null, false));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
                if (sentMsgId[0] != 0) ids.add(sentMsgId[0]);
                MessagesController.getInstance(UserConfig.selectedAccount).deleteMessages(
                    ids, null, null, dialogId, false, 0, false, 0L, null, 0);
            }, seconds * 1000L);
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "👻 Сообщение удалится через " + seconds + "с", Toast.LENGTH_SHORT).show());
        } catch (NumberFormatException e) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Укажи число секунд", Toast.LENGTH_SHORT).show());
        }
    }

    private static void handleAutoReply(String arg, long dialogId) {
        if (arg.equals("on")) {
            autoReplyEnabled = true;
            sendLocal(dialogId, "✅ Автоответчик включён");
        } else if (arg.equals("off")) {
            autoReplyEnabled = false;
            sendLocal(dialogId, "❌ Автоответчик выключён");
        } else if (arg.isEmpty()) {
            waitingForAutoReply = true;
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, "💬 Напишите текст автоответчика:", android.widget.Toast.LENGTH_SHORT).show());
        } else {
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, "❌ Формат: /autoreply on/off или просто /autoreply", android.widget.Toast.LENGTH_SHORT).show());
        }
    }

    private static void handleRemind(String arg, long dialogId) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            sendLocal(dialogId, "❌ Формат: /remind <секунды> <текст>");
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[0]);
            String msg = parts[1];
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                sendLocal(dialogId, "⏰ Напоминание: " + msg), seconds * 1000L);
            sendLocal(dialogId, "✅ Напомню через " + seconds + "с");
        } catch (NumberFormatException e) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Укажи число секунд", Toast.LENGTH_SHORT).show());
        }
    }

    // Простой eval для калькулятора
    private static double eval(String expr) {
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int c) {
                while (ch == ' ') nextChar();
                if (ch == c) { nextChar(); return true; }
                return false;
            }
            double parse() { nextChar(); double v = parseExpr(); if (pos < expr.length()) throw new RuntimeException(); return v; }
            double parseExpr() {
                double v = parseTerm();
                while (true) {
                    if (eat('+')) v += parseTerm();
                    else if (eat('-')) v -= parseTerm();
                    else return v;
                }
            }
            double parseTerm() {
                double v = parseFactor();
                while (true) {
                    if (eat('*')) v *= parseFactor();
                    else if (eat('/')) v /= parseFactor();
                    else return v;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double v;
                int start = pos;
                if (eat('(')) { v = parseExpr(); eat(')'); }
                else {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    v = Double.parseDouble(expr.substring(start, pos));
                }
                return v;
            }
        }.parse();
    }

    private static void handleAi(String arg, long dialogId, MessageObject replyToMsg) {
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            addLog("❌ handleAi: ctx is null");
            return;
        }
        if (arg == null) arg = "";
        String argTrimmed = arg.trim();
        if (argTrimmed.equals("api")) {
            aiWizardStep = AI_WIZARD_URL;
            aiWizardDialogId = dialogId;
            aiWizardUrl = "";
            aiWizardModel = "";
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Отправьте URL провайдера:\n(Например https://generativelanguage.googleapis.com/v1beta)", Toast.LENGTH_LONG).show());
            return;
        }
        if (argTrimmed.equals("role")) {
            int newRole = AiManager.nextRole(ctx);
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Роль: " + AiManager.getRoleName(newRole), Toast.LENGTH_SHORT).show());
            return;
        }
        if (argTrimmed.equals("clean")) {
            AiManager.clearHistory(ctx, dialogId);
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "🧹 История очищена", Toast.LENGTH_SHORT).show());
            return;
        }
        if (argTrimmed.equals("vision")) {
            aiWizardStep = AI_WIZARD_VISION_URL;
            aiWizardDialogId = dialogId;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Vision: отправьте URL провайдера:", Toast.LENGTH_LONG).show());
            return;
        }
        if (argTrimmed.equals("vision on")) {
            visionEnabled = true;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "👁 Vision включён", Toast.LENGTH_SHORT).show());
            return;
        }
        if (argTrimmed.equals("vision off")) {
            visionEnabled = false;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "👁 Vision выключен", Toast.LENGTH_SHORT).show());
            return;
        }
        if (argTrimmed.equals("clean mem")) {
            AiManager.clearLongMemory(ctx);
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "\u0414\u043e\u043b\u0433\u0430\u044f \u043f\u0430\u043c\u044f\u0442\u044c \u043e\u0447\u0438\u0449\u0435\u043d\u0430", Toast.LENGTH_SHORT).show());
            return;
        }
        if (argTrimmed.equals("user")) {
            boolean nowOff = aiUserChats.contains(dialogId);
            if (nowOff) {
                aiUserChats.remove(dialogId);
            } else {
                aiUserChats.add(dialogId);
            }
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, nowOff ? "🤖 AI User выключен в этом чате" : "🤖 AI User включён в этом чате", Toast.LENGTH_SHORT).show());
            return;
        }
        if (argTrimmed.equals("user off")) {
            aiUserChats.clear();
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "🤖 AI User выключен везде", Toast.LENGTH_SHORT).show());
            return;
        }
        // /ai <вопрос>
        if (argTrimmed.isEmpty()) {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ Формат: /ai <вопрос>", Toast.LENGTH_SHORT).show());
            return;
        }
        if (!AiManager.isConfigured(ctx)) {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ AI не настроен. Используй /ai api", Toast.LENGTH_SHORT).show());
            return;
        }
        String question = argTrimmed;
        if (replyToMsg != null) {
            String replyText = replyToMsg.messageOwner != null ? replyToMsg.messageOwner.message : null;
            if (replyText != null && !replyText.isEmpty()) {
                question = "[Пользователь реплайнул на сообщение: \"" + replyText + "\"]\n" + question;
            }
        }
        final String finalQuestion = question;
        try {
            java.io.FileWriter dbg = new java.io.FileWriter("/sdcard/ai_debug.txt", true);
            dbg.write("handleAi called, question=" + finalQuestion + "\n");
            dbg.close();
        } catch (Exception ignored) {}
                AiManager.ask(ctx, dialogId, finalQuestion, new AiManager.AiCallback() {
            @Override
            public void onResult(String result) {
                if (result == null) result = "(пустой ответ)";
                String q = finalQuestion.length() > 40 ? finalQuestion.substring(0, 40) + "..." : finalQuestion;
                String formatted = "───「 " + q + " 」───\n" + result;
                sendAiResult(dialogId, formatted, replyToMsg, UserConfig.selectedAccount);
            }
            @Override
            public void onError(String error) {
                addLog("AI ERROR: " + error);
                if (error == null) error = "Неизвестная ошибка";
                sendLocal(dialogId, "❌ " + error);
            }
        });
    }

    private static void handleExit(long dialogId) {
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (aiWizardStep != AI_WIZARD_NONE) {
            aiWizardStep = AI_WIZARD_NONE;
            aiWizardUrl = "";
            aiWizardModel = "";
            aiWizardDialogId = 0;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "✅ Вышел из режима настройки AI", Toast.LENGTH_SHORT).show());
        } else {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ Ты не в режиме настройки", Toast.LENGTH_SHORT).show());
        }
    }

    // Вызывается из SendMessagesHelper для перехвата wizard шагов
    public static boolean handleAiWizardStep(String text, long dialogId) {
        if (aiWizardStep == AI_WIZARD_NONE) return false;
        if (aiWizardStep == AI_WIZARD_VISION_URL) {
            aiWizardUrl = text.trim();
            aiWizardStep = AI_WIZARD_VISION_MODEL;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Vision: модель (например gpt-4o):", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_VISION_MODEL) {
            aiWizardModel = text.trim();
            aiWizardStep = AI_WIZARD_VISION_TOKEN;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Vision: API ключ:", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_VISION_TOKEN) {
            AiManager.saveVisionSettings(ctx, aiWizardUrl, aiWizardModel, text.trim());
            aiWizardStep = AI_WIZARD_NONE;
            aiWizardUrl = "";
            aiWizardModel = "";
            aiWizardDialogId = 0;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "✅ Vision настроен!", Toast.LENGTH_SHORT).show());
            return true;
        }
        if (text.startsWith("/")) return false;
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (aiWizardStep == AI_WIZARD_URL) {
            aiWizardUrl = text.trim();
            aiWizardStep = AI_WIZARD_MODEL;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Модель ИИ:\n(Например gemini-2.5-flash)", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_MODEL) {
            aiWizardModel = text.trim();
            aiWizardStep = AI_WIZARD_TOKEN;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Свой API ключ:", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_TOKEN) {
            String token = text.trim();
            AiManager.saveSettings(ctx, aiWizardUrl, aiWizardModel, token);
            aiWizardStep = AI_WIZARD_NONE;
            aiWizardUrl = "";
            aiWizardModel = "";
            aiWizardDialogId = 0;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "✅ AI настроен успешно!", Toast.LENGTH_SHORT).show());
            return true;
        }
        return false;
    }

    public static int getAiWizardStep() { return aiWizardStep; }

    public static boolean isInvisibleMode() { return invisibleMode; }
    public static boolean isVisionEnabled() { return visionEnabled; }
    public static boolean isAutoReplyEnabled() { return autoReplyEnabled; }
    public static String getAutoReplyMessage() { return autoReplyMessage; }
    public static void setAutoReplyMessage(String msg) { autoReplyMessage = msg; }
    public static boolean isWaitingForAutoReply() { return waitingForAutoReply; }
    public static void setWaitingForAutoReply(boolean v) { waitingForAutoReply = v; }

    // /ai user feature accessors
    public static void setLastAiError(String error) { lastAiError = error; }

    public static void addGroupMessage(long dialogId, String senderName, String senderUsername, String text) {
        if (text == null || text.isEmpty()) return;
        String entry = senderName + (senderUsername != null && !senderUsername.isEmpty() ? " (@" + senderUsername + ")" : "") + ": " + text;
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        if (cache == null) {
            cache = new java.util.LinkedList<>();
            groupMessageCache.put(dialogId, cache);
        }
        cache.addLast(entry);
        if (cache.size() > GROUP_CACHE_SIZE) cache.removeFirst();
    }

    public static String getGroupHistory(long dialogId) {
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        if (cache == null || cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String entry : cache) sb.append(entry).append("\n");
        return sb.toString().trim();
    }

    public static String getGroupHistory(long dialogId, int limit) {
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        if (cache == null || cache.isEmpty()) return "";
        java.util.List<String> list = new java.util.ArrayList<>(cache);
        int start = Math.max(0, list.size() - limit);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < list.size(); i++) sb.append(list.get(i)).append("\n");
        return sb.toString().trim();
    }

    private static final HashMap<Long, Long> webSearchCooldown = new HashMap<>();
    private static final HashMap<Long, java.util.HashSet<Long>> rudeUsers = new HashMap<>();

    public static boolean isRudeUser(long dialogId, long userId) {
        java.util.HashSet<Long> set = rudeUsers.get(dialogId);
        return set != null && set.contains(userId);
    }

    public static void markRudeUser(long dialogId, long userId) {
        java.util.HashSet<Long> set = rudeUsers.get(dialogId);
        if (set == null) { set = new java.util.HashSet<>(); rudeUsers.put(dialogId, set); }
        set.add(userId);
    }

    public static void forgivUser(long dialogId, long userId) {
        java.util.HashSet<Long> set = rudeUsers.get(dialogId);
        if (set != null) set.remove(userId);
    }
    private static final long WEB_SEARCH_CD_MS = 60_000L;

    public static boolean canWebSearch(long dialogId) {
        Long last = webSearchCooldown.get(dialogId);
        return last == null || (System.currentTimeMillis() - last) >= WEB_SEARCH_CD_MS;
    }

    public static void markWebSearchUsed(long dialogId) {
        webSearchCooldown.put(dialogId, System.currentTimeMillis());
    }

    public static String getAuthorByQuoteText(long dialogId, String quoteText) {
        if (quoteText == null || quoteText.isEmpty()) return null;
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        if (cache == null) return null;
        String lower = quoteText.toLowerCase().trim();
        for (String entry : cache) {
            // entry format: "Имя (@username): текст"
            int colonIdx = entry.indexOf(": ");
            if (colonIdx == -1) continue;
            String entryText = entry.substring(colonIdx + 2).toLowerCase().trim();
            if (entryText.contains(lower) || lower.contains(entryText)) {
                return entry.substring(0, colonIdx); // возвращаем "Имя (@username)"
            }
        }
        return null;
    }

    public static boolean isAiUserEnabled(long dialogId) {
        return aiUserChats.contains(dialogId);
    }

    public static void sendReactionDirect(long dialogId, int msgId, String... emojis) {
        try {
            org.telegram.tgnet.TLRPC.TL_messages_sendReaction req = new org.telegram.tgnet.TLRPC.TL_messages_sendReaction();
            req.peer = org.telegram.messenger.MessagesController.getInstance(UserConfig.selectedAccount).getInputPeer(dialogId);
            req.msg_id = msgId;
            req.flags |= 1;
            for (String emoji : emojis) {
                org.telegram.tgnet.TLRPC.TL_reactionEmoji r = new org.telegram.tgnet.TLRPC.TL_reactionEmoji();
                r.emoticon = emoji;
                req.reaction.add(r);
            }
            org.telegram.tgnet.ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(req, (response, error) -> {
                if (response != null) {
                    org.telegram.messenger.MessagesController.getInstance(UserConfig.selectedAccount).processUpdates((org.telegram.tgnet.TLRPC.Updates) response, false);
                }
            });
        } catch (Exception e) {
            addLog("❌ Реакция ошибка: " + e.getMessage());
        }
    }

    public static String[] getReactionForTone(String tone) {
        if (tone == null) return null;
        switch (tone) {
            case "positive": return Math.random() < 0.5 ? new String[]{"👍"} : new String[]{"😊"};
            case "funny":    return Math.random() < 0.5 ? new String[]{"😂"} : null;
            case "negative": return new String[]{"💩"};
            default:         return null;
        }
    }

    public static void cacheMyMessageId(long dialogId, int messageId) {
        LinkedList<Integer> list = myMessageIdsCache.get(dialogId);
        if (list == null) {
            list = new LinkedList<>();
            myMessageIdsCache.put(dialogId, list);
        }
        list.addLast(messageId);
        while (list.size() > MAX_CACHED_MESSAGE_IDS) {
            list.removeFirst();
        }
    }

    public static boolean isMyMessageId(long dialogId, int messageId) {
        LinkedList<Integer> list = myMessageIdsCache.get(dialogId);
        return list != null && list.contains(messageId);
    }

    public static boolean canAiUserReply(long dialogId) {
        Long last = aiUserCooldown.get(dialogId);
        if (last == null) return true;
        return System.currentTimeMillis() - last >= AI_USER_COOLDOWN_MS;
    }

    public static long getCooldownLeft(long dialogId) {
        Long last = aiUserCooldown.get(dialogId);
        if (last == null) return 0;
        return Math.max(0, AI_USER_COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

    public static void markAiUserReplied(long dialogId) {
        aiUserCooldown.put(dialogId, System.currentTimeMillis());
    }

    private static boolean myMessageObserverRegistered = false;

    public static void ensureMyMessageObserverRegistered() {
        if (myMessageObserverRegistered) return;
        myMessageObserverRegistered = true;
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int account, Object... args) {
                if (id == NotificationCenter.messageReceivedByServer) {
                    try {
                        Integer newMsgId = (Integer) args[1];
                        Long did = (Long) args[3];
                        if (newMsgId != null && did != null) {
                            cacheMyMessageId(did, newMsgId);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }, NotificationCenter.messageReceivedByServer);
    }
}
