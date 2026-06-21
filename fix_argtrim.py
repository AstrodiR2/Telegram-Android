filepath = "TMessagesProj/src/main/java/org/telegram/messenger/CommandHandler.java"
with open(filepath, "r") as f:
    content = f.read()

old = '''        if (arg == null) arg = "";
        if (arg.trim().equals("api")) {'''

new = '''        if (arg == null) arg = "";
        String argTrimmed = arg.trim();
        if (argTrimmed.equals("api")) {'''

if old in content:
    content = content.replace(old, new, 1)
    # Now replace all remaining arg.trim() with argTrimmed in handleAi
    content = content.replace('if (arg.trim().equals("role"))', 'if (argTrimmed.equals("role"))', 1)
    content = content.replace('if (arg.trim().equals("clean")) {', 'if (argTrimmed.equals("clean")) {', 1)
    content = content.replace('if (arg.trim().equals("clean mem"))', 'if (argTrimmed.equals("clean mem"))', 1)
    content = content.replace('if (arg.trim().equals("user"))', 'if (argTrimmed.equals("user"))', 1)
    content = content.replace('if (arg.trim().equals("user off"))', 'if (argTrimmed.equals("user off"))', 1)
    content = content.replace('if (arg.trim().isEmpty())', 'if (argTrimmed.isEmpty())', 1)
    content = content.replace('String question = arg.trim();', 'String question = argTrimmed;', 1)
    with open(filepath, "w") as f:
        f.write(content)
    print("OK: argTrimmed optimization")
else:
    print("ERROR: pattern not found")
