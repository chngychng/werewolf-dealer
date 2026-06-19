package com.example.werewolf;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WerewolfDealer {
    private static int PORT = 8080;
    private static final Object LOCK = new Object();
    private static GameState state = GameState.empty();

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            PORT = Integer.parseInt(args[0]);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/api/", new ApiHandler());
        server.createContext("/", new StaticHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("狼人杀法官发牌器已启动：");
        for (String link : localLinks()) {
            System.out.println("  " + link);
        }
        System.out.println("按 Ctrl+C 关闭。Windows 防火墙提示时，请允许 Java 专用网络访问。");
    }

    private static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCommonHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 204, "");
                return;
            }
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                String body = readBody(exchange);

                if ("GET".equals(method) && path.equals("/api/modes")) {
                    sendJson(exchange, 200, json(modeList()));
                    return;
                }
                if ("POST".equals(method) && path.equals("/api/game/start")) {
                    handleStart(exchange, body);
                    return;
                }
                if ("POST".equals(method) && path.equals("/api/game/reset")) {
                    synchronized (LOCK) {
                        state = GameState.empty();
                    }
                    sendJson(exchange, 200, json(map("ok", true)));
                    return;
                }
                if ("GET".equals(method) && (path.equals("/api/game") || path.equals("/api/judge"))) {
                    synchronized (LOCK) {
                        sendJson(exchange, 200, json(toGameView(path.equals("/api/judge"))));
                    }
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/api/player/")) {
                    int number = Integer.parseInt(path.substring("/api/player/".length()));
                    handlePlayer(exchange, number);
                    return;
                }
                if ("POST".equals(method) && path.equals("/api/action")) {
                    handleAction(exchange, body);
                    return;
                }
                if ("POST".equals(method) && path.equals("/api/night/step")) {
                    handleNightStep(exchange, body);
                    return;
                }
                if ("GET".equals(method) && path.equals("/api/links")) {
                    sendJson(exchange, 200, json(map(
                            "links", localLinks(),
                            "playerLinks", playerLinks(),
                            "note", "手机和电脑必须在同一个 Wi-Fi / 局域网。Windows 防火墙若提示 Java 访问网络，选择允许专用网络。"
                    )));
                    return;
                }

                sendJson(exchange, 404, json(map("error", "API 不存在: " + path)));
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, json(map("error", e.getMessage() == null ? "服务器错误" : e.getMessage())));
            }
        }
    }

    private static void handleStart(HttpExchange exchange, String body) throws IOException {
        synchronized (LOCK) {
            String modeId = getJsonString(body, "modeId");
            int playerCount = getJsonInt(body, "playerCount", 12);
            Mode mode = modes().get(modeId);
            if (mode == null) {
                sendJson(exchange, 400, json(map("error", "未知模式: " + modeId)));
                return;
            }
            if (!mode.supportedPlayerCounts.contains(playerCount)) {
                sendJson(exchange, 400, json(map("error", "该模式只支持这些人数: " + mode.supportedPlayerCounts)));
                return;
            }

            List<Role> roles = new ArrayList<>(mode.baseRoles);
            while (roles.size() < playerCount) roles.add(villager());
            if (roles.size() != playerCount) {
                sendJson(exchange, 400, json(map("error", "角色数量与玩家数量不一致")));
                return;
            }
            Collections.shuffle(roles);

            Map<Integer, PlayerCard> assignments = new LinkedHashMap<>();
            for (int i = 1; i <= playerCount; i++) {
                assignments.put(i, new PlayerCard(i, roles.get(i - 1)));
            }
            state = new GameState(UUID.randomUUID().toString(), mode, playerCount, assignments, new ArrayList<>(), 0, Instant.now());
            sendJson(exchange, 200, json(toGameView(true)));
        }
    }

    private static void handlePlayer(HttpExchange exchange, int number) throws IOException {
        synchronized (LOCK) {
            if (!state.started()) {
                sendJson(exchange, 400, json(map("error", "还没有开局")));
                return;
            }
            PlayerCard card = state.assignments.get(number);
            if (card == null) {
                sendJson(exchange, 400, json(map("error", "没有这个号码")));
                return;
            }
            sendJson(exchange, 200, json(toPlayerView(card)));
        }
    }

    private static void handleNightStep(HttpExchange exchange, String body) throws IOException {
        synchronized (LOCK) {
            if (!state.started()) {
                sendJson(exchange, 400, json(map("error", "还没有开局")));
                return;
            }
            int index = getJsonInt(body, "index", state.currentStepIndex);
            int max = state.mode.nightSteps.size() - 1;
            if (index < 0) index = 0;
            if (index > max) index = max;
            state = new GameState(state.gameId, state.mode, state.playerCount, state.assignments, state.actions, index, state.createdAt);
            sendJson(exchange, 200, json(toGameView(true)));
        }
    }

    private static void handleAction(HttpExchange exchange, String body) throws IOException {
        synchronized (LOCK) {
            if (!state.started()) {
                sendJson(exchange, 400, json(map("error", "还没有开局")));
                return;
            }
            int actorNumber = getJsonInt(body, "actorNumber", -1);
            String actionType = getJsonString(body, "actionType");
            String extra = getJsonString(body, "extra");
            List<Integer> targets = getJsonIntArray(body, "targets");

            PlayerCard actor = state.assignments.get(actorNumber);
            if (actor == null) {
                sendJson(exchange, 400, json(map("error", "没有这个操作号码")));
                return;
            }
            for (int t : targets) {
                if (!state.assignments.containsKey(t)) {
                    sendJson(exchange, 400, json(map("error", "目标号码不存在: " + t)));
                    return;
                }
            }
            if ("witch-save".equals(actionType) && actor.role.name.equals("女巫") && currentStep() != null && currentStep().roleNames.contains("女巫")) {
                Integer killed = latestTargetOf("wolf-kill");
                if (killed != null && killed == actor.number) {
                    sendJson(exchange, 400, json(map("error", "女巫不能自救")));
                    return;
                }
            }
            List<ActionChoice> allowedNow = availableChoicesFor(actor);
            boolean allowed = allowedNow.stream().anyMatch(c -> c.type.equals(actionType));
            if (!allowed) {
                NightStep step = currentStep();
                sendJson(exchange, 400, json(map("error", "现在不是这个身份/技能的操作轮次。当前轮次：" + (step == null ? "无" : step.title))));
                return;
            }
            ActionChoice choice = allowedNow.stream().filter(c -> c.type.equals(actionType)).findFirst().orElseThrow();
            if (targets.size() != choice.targetCount) {
                if (!(choice.optional && targets.isEmpty())) {
                    sendJson(exchange, 400, json(map("error", "目标数量不对，应选择 " + choice.targetCount + " 个目标")));
                    return;
                }
            }
            if ("choose-idol".equals(actionType) && !targets.isEmpty() && targets.get(0) == actor.number) {
                sendJson(exchange, 400, json(map("error", "混血儿不能选择自己作为榜样")));
                return;
            }
            NightStep stepBefore = currentStep();
            int beforeIndex = state.currentStepIndex;
            String result = makeResult(actor, actionType, targets, extra);
            NightAction action = new NightAction(actor.number, actor.role.name, actionType, targets, extra, result, Instant.now());
            state.actions.add(action);
            advanceToNextStep();
            NightStep stepAfter = currentStep();
            sendJson(exchange, 200, json(map(
                    "ok", true,
                    "result", result == null ? "" : result,
                    "action", actionToMap(action),
                    "autoAdvanced", state.currentStepIndex != beforeIndex,
                    "previousStep", stepToMap(stepBefore, beforeIndex),
                    "currentStepIndex", state.currentStepIndex,
                    "currentStep", stepToMap(stepAfter, state.currentStepIndex)
            )));
        }
    }

    private static void advanceToNextStep() {
        if (!state.started() || state.mode.nightSteps.isEmpty()) return;
        int max = state.mode.nightSteps.size() - 1;
        int next = Math.min(state.currentStepIndex + 1, max);
        if (next != state.currentStepIndex) {
            state = new GameState(state.gameId, state.mode, state.playerCount, state.assignments, state.actions, next, state.createdAt);
        }
    }

    private static Map<String, Object> toGameView(boolean includeRoles) {
        if (!state.started()) {
            return map(
                    "started", false,
                    "gameId", null,
                    "modeId", null,
                    "playerCount", 0,
                    "players", List.of(),
                    "nightSteps", List.of(),
                    "currentStepIndex", 0,
                    "currentStep", null,
                    "autoAdvance", true,
                    "baseRoles", List.of(),
                    "createdAt", null,
                    "links", localLinks(),
                    "playerLinks", playerLinks(),
                    "actions", List.of(),
                    "nightResult", emptyNightResult()
            );
        }

        List<Object> players = new ArrayList<>();
        for (PlayerCard card : state.assignments.values()) {
            players.add(map(
                    "number", card.number,
                    "role", includeRoles ? card.role.name : null,
                    "team", includeRoles ? card.role.team : null
            ));
        }
        return map(
                "started", true,
                "gameId", state.gameId,
                "modeId", state.mode.id,
                "playerCount", state.playerCount,
                "players", players,
                "nightSteps", stepListToMaps(state.mode.nightSteps),
                "currentStepIndex", state.currentStepIndex,
                "currentStep", stepToMap(currentStep(), state.currentStepIndex),
                "autoAdvance", true,
                "baseRoles", state.mode.baseRoles.stream().map(r -> r.name).toList(),
                "createdAt", DateTimeFormatter.ISO_LOCAL_DATE_TIME.withLocale(Locale.CHINA).withZone(ZoneId.systemDefault()).format(state.createdAt),
                "links", localLinks(),
                "playerLinks", playerLinks(),
                "actions", state.actions.stream().map(WerewolfDealer::actionToMap).toList(),
                "nightResult", nightResult()
        );
    }

    private static Map<String, Object> toPlayerView(PlayerCard card) {
        Role role = card.role;
        List<ActionChoice> available = availableChoicesFor(card);
        boolean myTurn = currentStep() != null && currentStep().roleNames.contains(role.name);
        return map(
                "number", card.number,
                "role", role.name,
                "team", role.team,
                "description", role.description,
                "notes", role.notes,
                "visibleWolfNumbers", wolfNumbersVisibleTo(card),
                "currentStepIndex", state.currentStepIndex,
                "autoAdvance", true,
                "myTurn", myTurn,
                "turnMessage", myTurn ? "请完成你的操作，提交后系统会自动进入下一步。" : "请闭眼等待。",
                "nightInfo", nightInfoFor(card),
                "actionChoices", available.stream().map(WerewolfDealer::actionChoiceToMap).toList()
        );
    }

    private static List<Integer> wolfNumbersVisibleTo(PlayerCard card) {
        Role role = card.role;
        if (!role.team.equals("狼人阵营") || role.secretWolf) return List.of();
        return state.assignments.values().stream()
                .filter(p -> p.role.team.equals("狼人阵营"))
                .filter(p -> !p.role.secretWolf)
                .map(p -> p.number)
                .sorted()
                .toList();
    }

    private static Role mirrorShownRole(PlayerCard target) {
        if (!target.role.name.equals("觉醒隐狼")) return target.role;
        for (int i = state.actions.size() - 1; i >= 0; i--) {
            NightAction action = state.actions.get(i);
            if (action.actorNumber == target.number && action.actionType.equals("imitate") && !action.targets.isEmpty()) {
                PlayerCard learned = state.assignments.get(action.targets.get(0));
                if (learned != null) return learned.role;
            }
        }
        return target.role;
    }

    private static boolean isPoisonImmune(PlayerCard card) {
        return card != null && (card.role.name.equals("舞者") || card.role.name.equals("假面"));
    }

    private static Map<String, Object> nightInfoFor(PlayerCard card) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (card.role.name.equals("女巫")) {
            Integer killed = latestTargetOf("wolf-kill");
            boolean selfKilled = killed != null && killed == card.number;
            info.put("wolfKillTarget", killed);
            info.put("witchSelfSaveBlocked", selfKilled);
            info.put("wolfKillText", killed == null
                    ? "今晚暂未记录狼刀目标。"
                    : (selfKilled ? "今晚死亡：你自己（" + killed + "号）。规则：女巫不能自救。" : "今晚死亡：" + killed + "号"));
        }
        if (card.role.name.equals("猎人")) {
            boolean poisoned = isTargetedBy("witch-poison", card.number);
            info.put("hunterCanShoot", !poisoned);
            info.put("hunterStatusText", poisoned ? "当前开枪状态：不能开枪（已被女巫毒药命中）。" : "当前开枪状态：可以开枪。");
        }
        return info;
    }

    private static Integer latestTargetOf(String actionType) {
        for (int i = state.actions.size() - 1; i >= 0; i--) {
            NightAction a = state.actions.get(i);
            if (a.actionType.equals(actionType) && !a.targets.isEmpty()) return a.targets.get(0);
        }
        return null;
    }

    private static boolean isTargetedBy(String actionType, int number) {
        for (NightAction a : state.actions) {
            if (a.actionType.equals(actionType) && a.targets.contains(number)) return true;
        }
        return false;
    }

    private static String makeResult(PlayerCard actor, String actionType, List<Integer> targets, String extra) {
        if ("hunter-status".equals(actionType)) {
            boolean poisoned = isTargetedBy("witch-poison", actor.number);
            return poisoned ? "猎人开枪状态：不能开枪（已被女巫毒药命中）。" : "猎人开枪状态：可以开枪。";
        }
        if (targets.isEmpty()) {
            if ("witch-save".equals(actionType)) {
                Integer killed = latestTargetOf("wolf-kill");
                return killed == null ? "已记录：女巫使用解药，但当前没有狼刀目标。" : "已记录：女巫使用解药救 " + killed + "号。";
            }
            if ("witch-skip".equals(actionType)) return "已记录：女巫不救不毒。";
            return "已记录：空过/无目标";
        }
        if ("choose-idol".equals(actionType)) {
            return "已记录：混血儿选择 " + targets.get(0) + "号作为榜样。系统不会向混血儿反馈榜样身份或阵营。";
        }
        if ("seer-check".equals(actionType)) {
            PlayerCard target = state.assignments.get(targets.get(0));
            return target.number + "号查验结果：" + (target.role.team.equals("狼人阵营") ? "狼人阵营" : "好人阵营");
        }
        if ("mirror-check".equals(actionType)) {
            PlayerCard target = state.assignments.get(targets.get(0));
            Role shown = mirrorShownRole(target);
            return target.number + "号具体身份：" + shown.name + "（" + shown.team + "）";
        }
        if ("imitate".equals(actionType)) {
            PlayerCard target = state.assignments.get(targets.get(0));
            return "觉醒隐狼学习 " + target.number + "号，目标底牌：" + target.role.name + "（" + target.role.team + "）。";
        }
        if ("wolf-kill".equals(actionType)) return "已记录狼队刀口：" + targets.get(0) + "号。";
        if ("guard".equals(actionType)) return "已记录守卫守护：" + targets.get(0) + "号。";
        if ("witch-poison".equals(actionType)) {
            PlayerCard target = state.assignments.get(targets.get(0));
            if (isPoisonImmune(target)) {
                return "已记录女巫毒药目标：" + targets.get(0) + "号。" + target.role.name + "免疫女巫毒药，不会因毒药倒牌。";
            }
            return "已记录女巫毒药目标：" + targets.get(0) + "号。";
        }
        if ("charm".equals(actionType)) return "已记录狼美人魅惑：" + targets.get(0) + "号。";
        if ("dance".equals(actionType)) {
            long wolves = targets.stream().map(n -> state.assignments.get(n)).filter(p -> p.role.team.equals("狼人阵营")).count();
            long good = targets.size() - wolves;
            String prefix = "舞池结算仅供参考，假面面具会改变舞池阵营计算。当前未戴面具时：";
            if (wolves == 0 || good == 0) return prefix + "三人同阵营，无人因舞池出局。";
            String losingTeam = wolves < good ? "狼人阵营" : "好人阵营";
            List<Integer> dying = targets.stream().filter(n -> state.assignments.get(n).role.team.equals(losingTeam)).toList();
            return prefix + losingTeam + "人数少，" + dying + " 号因舞池出局。";
        }
        if ("mask".equals(actionType)) {
            return targets.get(0) + "号被记录为戴面具目标；舞池结算时把该玩家阵营反转计算。";
        }
        return "已记录：" + actor.role.name + " -> " + targets;
    }


    private static Map<String, Object> emptyNightResult() {
        return map(
                "finished", false,
                "summary", "第一晚尚未结束。",
                "deaths", List.of(),
                "details", List.of()
        );
    }

    private static Map<String, Object> nightResult() {
        if (!state.started()) return emptyNightResult();
        boolean finished = state.currentStepIndex >= state.mode.nightSteps.size() - 1;
        List<String> details = new ArrayList<>();
        LinkedHashSet<Integer> deaths = new LinkedHashSet<>();

        Integer wolfKill = latestTargetOf("wolf-kill");
        Integer guardTarget = latestTargetOf("guard");
        Integer poisonTarget = latestTargetOf("witch-poison");
        boolean witchSaved = hasAction("witch-save");

        if (wolfKill == null) {
            details.add("狼人未记录击杀目标。");
        } else {
            boolean guarded = guardTarget != null && guardTarget.equals(wolfKill);
            if (witchSaved && guarded) {
                deaths.add(wolfKill);
                details.add(wolfKill + "号为狼刀目标，同时被守卫守护和女巫解药救起，按同守同救死亡处理。");
            } else if (witchSaved) {
                details.add(wolfKill + "号为狼刀目标，已被女巫解药救起。");
            } else if (guarded) {
                details.add(wolfKill + "号为狼刀目标，已被守卫守护，没有因狼刀倒牌。");
            } else {
                deaths.add(wolfKill);
                details.add(wolfKill + "号为狼刀目标，昨夜倒牌。");
            }
        }

        if (poisonTarget != null) {
            PlayerCard poisoned = state.assignments.get(poisonTarget);
            if (isPoisonImmune(poisoned)) {
                details.add(poisonTarget + "号被女巫毒药命中，但" + poisoned.role.name + "免疫女巫毒药，没有因毒药倒牌。");
            } else {
                deaths.add(poisonTarget);
                details.add(poisonTarget + "号被女巫毒药命中，昨夜倒牌。");
            }
        }

        List<Integer> deathList = new ArrayList<>(deaths);
        Collections.sort(deathList);
        String summary;
        if (!finished) {
            summary = "第一晚尚未结束，当前结果仅为临时预览。";
        } else if (deathList.isEmpty()) {
            summary = "昨夜平安夜。";
        } else {
            summary = "昨夜倒牌：" + joinNumbers(deathList) + "号。";
        }
        return map(
                "finished", finished,
                "summary", summary,
                "deaths", deathList,
                "details", List.of()
        );
    }

    private static boolean hasAction(String actionType) {
        for (NightAction a : state.actions) {
            if (a.actionType.equals(actionType)) return true;
        }
        return false;
    }

    private static String joinNumbers(List<Integer> numbers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(numbers.get(i));
        }
        return sb.toString();
    }

    private static List<ActionChoice> actionChoicesFor(Role role) {
        List<ActionChoice> choices = new ArrayList<>();
        String name = role.name;
        if (name.equals("混血儿")) choices.add(new ActionChoice("choose-idol", "选择一名玩家作为榜样", 1, false));
        if (name.equals("预言家")) choices.add(new ActionChoice("seer-check", "查验一名玩家阵营", 1, false));
        if (name.equals("魔镜少女")) choices.add(new ActionChoice("mirror-check", "查验一名玩家具体身份", 1, false));
        if (name.equals("守卫")) choices.add(new ActionChoice("guard", "守护一名玩家", 1, true));
        if (name.equals("女巫")) {
            choices.add(new ActionChoice("witch-save", "使用解药救今晚死亡玩家", 0, false));
            choices.add(new ActionChoice("witch-poison", "使用毒药毒一名玩家", 1, false));
            choices.add(new ActionChoice("witch-skip", "不救不毒", 0, false));
        }
        if (name.equals("狼人") || name.equals("狼美人")) choices.add(new ActionChoice("wolf-kill", "狼人选择击杀目标", 1, false));
        if (name.equals("狼美人")) choices.add(new ActionChoice("charm", "狼美人魅惑一名玩家", 1, true));
        if (name.equals("觉醒隐狼")) choices.add(new ActionChoice("imitate", "觉醒隐狼模仿/学习一名玩家", 1, true));
        if (name.equals("舞者")) choices.add(new ActionChoice("dance", "舞者选择 3 名玩家进入舞池", 3, true));
        if (name.equals("假面")) choices.add(new ActionChoice("mask", "假面给一名玩家戴面具", 1, true));
        if (name.equals("猎人")) choices.add(new ActionChoice("hunter-status", "查看并确认开枪状态", 0, false));
        return choices;
    }

    private static List<ActionChoice> availableChoicesFor(PlayerCard card) {
        Role role = card.role;
        NightStep step = currentStep();
        if (step == null || !step.roleNames.contains(role.name)) return List.of();
        return actionChoicesFor(role).stream()
                .filter(c -> step.actionTypes.contains(c.type))
                .filter(c -> !isActionBlockedForActor(card, c.type))
                .toList();
    }

    private static boolean isActionBlockedForActor(PlayerCard actor, String actionType) {
        if ("witch-save".equals(actionType) && actor.role.name.equals("女巫")) {
            Integer killed = latestTargetOf("wolf-kill");
            return killed != null && killed == actor.number; // 女巫不能自救
        }
        return false;
    }

    private static NightStep currentStep() {
        if (!state.started() || state.mode.nightSteps.isEmpty()) return null;
        int idx = Math.max(0, Math.min(state.currentStepIndex, state.mode.nightSteps.size() - 1));
        return state.mode.nightSteps.get(idx);
    }

    private static Map<String, Object> actionChoiceToMap(ActionChoice c) {
        return map("type", c.type, "label", c.label, "targetCount", c.targetCount, "optional", c.optional);
    }

    private static List<Object> stepListToMaps(List<NightStep> steps) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) list.add(stepToMap(steps.get(i), i));
        return list;
    }

    private static Map<String, Object> stepToMap(NightStep s, int index) {
        if (s == null) return null;
        return map(
                "index", index,
                "title", s.title,
                "instruction", s.instruction,
                "roleNames", s.roleNames,
                "actionTypes", s.actionTypes
        );
    }

    private static List<Object> modeList() {
        List<Object> list = new ArrayList<>();
        for (Mode m : modes().values()) {
            list.add(map(
                    "id", m.id,
                    "name", m.name,
                    "note", m.note,
                    "supportedPlayerCounts", m.supportedPlayerCounts,
                    "roles", m.baseRoles.stream().map(WerewolfDealer::roleToMap).toList()
            ));
        }
        return list;
    }

    private static Map<String, Object> roleToMap(Role r) {
        return map("name", r.name, "team", r.team, "description", r.description, "notes", r.notes, "secretWolf", r.secretWolf);
    }

    private static Map<String, Object> actionToMap(NightAction a) {
        return map(
                "actorNumber", a.actorNumber,
                "actorRole", a.actorRole,
                "actionType", a.actionType,
                "targets", a.targets,
                "extra", a.extra,
                "result", a.result,
                "at", a.at.toString()
        );
    }

    private static Map<String, Mode> modes() {
        Map<String, Mode> map = new LinkedHashMap<>();
        map.put("standard", new Mode(
                "standard",
                "标准 12 人：预女猎白混",
                "4 狼、3 民、混血儿、预言家、女巫、猎人、白痴。混血儿替代一张平民，首夜只选择榜样，不反馈榜样身份。流程：混血儿 → 普狼 → 预言家 → 女巫 → 猎人开枪状态；白痴首夜不睁眼。13 人额外加 1 平民。",
                List.of(12, 13),
                List.of(wolf(), wolf(), wolf(), wolf(), villager(), villager(), villager(), hybrid(), seer(), witch(), hunter(), idiot()),
                List.of(
                        step("混血儿睁眼", "混血儿选择一名玩家作为榜样。只记录榜样，不反馈榜样身份或阵营。", List.of("混血儿"), List.of("choose-idol")),
                        step("狼人睁眼", "所有狼人睁眼，统一选择首夜击杀目标。", List.of("狼人"), List.of("wolf-kill")),
                        step("预言家睁眼", "预言家查验一名玩家阵营。", List.of("预言家"), List.of("seer-check")),
                        step("女巫睁眼", "系统向女巫显示今晚死亡玩家；女巫选择救人、毒人或不救不毒。", List.of("女巫"), List.of("witch-save", "witch-poison", "witch-skip")),
                        step("猎人睁眼", "猎人查看并确认当前开枪状态。", List.of("猎人"), List.of("hunter-status")),
                        step("天亮", "第一晚流程结束。", List.of(), List.of())
                )
        ));
        map.put("mirror", new Mode(
                "mirror",
                "镜影/镜隐迷踪",
                "12 人：觉醒隐狼、3 狼、魔镜少女、女巫、猎人、守卫、4 民。首夜流程：普狼 → 觉醒隐狼模仿/学习 → 守卫 → 女巫 → 魔镜少女 → 猎人开枪状态。魔镜少女查已学习的觉醒隐狼时显示学习后的身份，未学习时显示觉醒隐狼。13 人额外加 1 平民。",
                List.of(12, 13),
                List.of(awakenedHiddenWolf(), wolf(), wolf(), wolf(), mirrorGirl(), witch(), hunter(), guard(), villager(), villager(), villager(), villager()),
                List.of(
                        step("狼人睁眼", "普通狼人睁眼选择击杀目标。觉醒隐狼不与普通狼人互知身份。", List.of("狼人"), List.of("wolf-kill")),
                        step("觉醒隐狼睁眼", "觉醒隐狼选择模仿/学习目标。", List.of("觉醒隐狼"), List.of("imitate")),
                        step("守卫睁眼", "守卫选择守护目标。", List.of("守卫"), List.of("guard")),
                        step("女巫睁眼", "系统向女巫显示今晚死亡玩家；女巫选择救人、毒人或不救不毒。", List.of("女巫"), List.of("witch-save", "witch-poison", "witch-skip")),
                        step("魔镜少女睁眼", "魔镜少女查验一名玩家具体身份。若目标为已学习觉醒隐狼，反馈学习后的身份。", List.of("魔镜少女"), List.of("mirror-check")),
                        step("猎人睁眼", "猎人查看并确认当前开枪状态。", List.of("猎人"), List.of("hunter-status")),
                        step("天亮", "第一晚流程结束。", List.of(), List.of())
                )
        ));
        map.put("dancer_mask", new Mode(
                "dancer_mask",
                "舞者假面 / 假面舞会",
                "12 人：假面、3 狼、预言家、女巫、舞者、白痴、4 民。本工具只做第一晚：普狼 → 预言家 → 女巫；白痴、舞者、假面首夜不唤醒。舞者、假面免疫女巫毒药。13 人额外加 1 平民。",
                List.of(12, 13),
                List.of(mask(), wolf(), wolf(), wolf(), seer(), witch(), dancer(), idiot(), villager(), villager(), villager(), villager()),
                List.of(
                        step("狼人睁眼", "普通狼人睁眼选择击杀目标。假面不与小狼见面。", List.of("狼人"), List.of("wolf-kill")),
                        step("预言家睁眼", "预言家查验一名玩家阵营。", List.of("预言家"), List.of("seer-check")),
                        step("女巫睁眼", "系统向女巫显示今晚死亡玩家；女巫选择救人、毒人或不救不毒。", List.of("女巫"), List.of("witch-save", "witch-poison", "witch-skip")),
                        step("天亮", "第一晚流程结束。", List.of(), List.of())
                )
        ));
        map.put("beauty_knight", new Mode(
                "beauty_knight",
                "狼美人骑士",
                "12 人：狼美人、3 狼、预言家、女巫、守卫、骑士、4 民。流程：预言家 → 守卫 → 狼人 → 女巫 → 狼美人；骑士首夜不睁眼。13 人额外加 1 平民。",
                List.of(12, 13),
                List.of(wolfBeauty(), wolf(), wolf(), wolf(), seer(), witch(), guard(), knight(), villager(), villager(), villager(), villager()),
                List.of(
                        step("预言家睁眼", "预言家查验一名玩家阵营。", List.of("预言家"), List.of("seer-check")),
                        step("守卫睁眼", "守卫选择守护目标。", List.of("守卫"), List.of("guard")),
                        step("狼人睁眼", "普通狼人和狼美人共同选择击杀目标。", List.of("狼人", "狼美人"), List.of("wolf-kill")),
                        step("女巫睁眼", "系统向女巫显示今晚死亡玩家；女巫选择救人、毒人或不救不毒。", List.of("女巫"), List.of("witch-save", "witch-poison", "witch-skip")),
                        step("狼美人睁眼", "狼美人选择魅惑目标。", List.of("狼美人"), List.of("charm")),
                        step("天亮", "第一晚流程结束。", List.of(), List.of())
                )
        ));
        return map;
    }

    private static NightStep step(String title, String instruction, List<String> roleNames, List<String> actionTypes) {
        return new NightStep(title, instruction, roleNames, actionTypes);
    }

    private static Role villager() { return new Role("平民", "好人阵营", "没有夜间技能，白天通过发言和投票找出狼人。", List.of("第一晚无操作。"), false); }
    private static Role hybrid() { return new Role("混血儿", "好人阵营", "首夜选择一名玩家作为榜样，自己的胜利条件跟随榜样。系统不会告诉你榜样身份或阵营。", List.of("底牌显示为混血儿。被预言家查验按好人结果处理。"), false); }
    private static Role wolf() { return new Role("狼人", "狼人阵营", "夜晚与狼队共同选择一名玩家击杀。", List.of("本工具会显示你可见的普通狼队友号码；特殊隐狼/假面是否互见按板子规则处理。"), false); }
    private static Role seer() { return new Role("预言家", "好人阵营", "每晚查验一名玩家的阵营。", List.of("查验结果只反馈好人阵营/狼人阵营。"), false); }
    private static Role witch() { return new Role("女巫", "好人阵营", "拥有一瓶解药和一瓶毒药；本工具规则固定为女巫不能自救。", List.of("本工具会在天亮后根据狼刀、解药、毒药、守卫记录生成昨夜倒牌/平安夜结果。"), false); }
    private static Role hunter() { return new Role("猎人", "好人阵营", "出局时按房规可开枪带走一名玩家。", List.of("第一晚会查看并确认开枪状态。"), false); }
    private static Role idiot() { return new Role("白痴", "好人阵营", "被放逐时可翻牌免疫出局，但之后通常失去投票权。", List.of("第一晚不睁眼。"), false); }
    private static Role guard() { return new Role("守卫", "好人阵营", "每晚守护一名玩家，防止其因狼刀出局。", List.of("是否可连续守同一人、同守同救是否奶穿，按你们房规执行。"), false); }
    private static Role knight() { return new Role("骑士", "好人阵营", "白天可发动决斗，用于验证一名玩家是否为狼人。", List.of("第一晚不睁眼。"), false); }
    private static Role wolfBeauty() { return new Role("狼美人", "狼人阵营", "狼人阵营特殊牌。夜晚可魅惑一名玩家；自己出局时通常带走被魅惑者。", List.of("本工具只记录首夜魅惑目标，不自动结算后续死亡。"), false); }
    private static Role awakenedHiddenWolf() { return new Role("觉醒隐狼", "狼人阵营", "狼人阵营特殊牌，不与普通狼人互知身份；可模仿/学习一名玩家获得相关技能。", List.of("本工具把它视为隐狼，普通狼人列表不会显示它。"), true); }
    private static Role mirrorGirl() { return new Role("魔镜少女", "好人阵营", "每晚查验一名玩家的具体身份。", List.of("若查验已学习的觉醒隐狼，显示其学习后的身份。未学习时显示觉醒隐狼。"), false); }
    private static Role dancer() { return new Role("舞者", "好人阵营", "从第二夜起选择三名玩家进入舞池；本工具只做第一晚，因此首夜不唤醒舞者。", List.of("舞池按阵营人数少的一方出局；假面会影响结算。舞者免疫女巫毒药。"), false); }
    private static Role mask() { return new Role("假面", "狼人阵营", "狼人阵营特殊牌，通常不与小狼见面；第二夜起可给玩家戴面具，影响舞池阵营结算。本工具只做第一晚，因此首夜不唤醒假面。", List.of("本工具把它视为隐狼，普通狼人列表不会显示它。假面免疫女巫毒药。"), true); }

    private static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCommonHeaders(exchange);
            String rawPath = exchange.getRequestURI().getPath();
            String file = rawPath.equals("/") ? "index.html" : rawPath.substring(1);
            if (file.contains("..")) {
                send(exchange, 403, "Forbidden");
                return;
            }
            Path path = Path.of("web", file);
            if (!Files.exists(path) || Files.isDirectory(path)) {
                send(exchange, 404, "Not Found");
                return;
            }
            String contentType = contentType(file);
            byte[] bytes = Files.readAllBytes(path);
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String contentType(String file) {
        if (file.endsWith(".html")) return "text/html";
        if (file.endsWith(".css")) return "text/css";
        if (file.endsWith(".js")) return "application/javascript";
        return "application/octet-stream";
    }

    private static void addCommonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String getJsonString(String json, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(json);
        if (!m.find()) return "";
        return unescapeJson(m.group(1));
    }

    private static int getJsonInt(String json, String key, int defaultValue) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) return defaultValue;
        return Integer.parseInt(m.group(1));
    }

    private static List<Integer> getJsonIntArray(String json, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\[(.*?)\\]").matcher(json);
        if (!m.find()) return List.of();
        String inside = m.group(1).trim();
        if (inside.isEmpty()) return List.of();
        List<Integer> list = new ArrayList<>();
        for (String part : inside.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) list.add(Integer.parseInt(s));
        }
        return list;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(json(String.valueOf(e.getKey()))).append(':').append(json(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                sb.append(json(x));
            }
            return sb.append(']').toString();
        }
        return json(String.valueOf(value));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static List<String> localLinks() {
        return localLinks("");
    }

    private static List<String> playerLinks() {
        return localLinks("/player.html");
    }

    private static List<String> localLinks(String pathSuffix) {
        Set<String> links = new LinkedHashSet<>();
        links.add("http://localhost:" + PORT + pathSuffix);

        List<String> lanLinks = new ArrayList<>();
        List<String> otherLinks = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    if (address.isLinkLocalAddress()) continue; // 过滤 169.254.x.x，这种地址手机通常访问不了

                    String url = "http://" + address.getHostAddress() + ":" + PORT + pathSuffix;
                    if (address.isSiteLocalAddress()) {
                        lanLinks.add(url); // 常见：192.168.x.x / 10.x.x.x / 172.16-31.x.x
                    } else {
                        otherLinks.add(url);
                    }
                }
            }
        } catch (Exception ignored) {}

        Collections.sort(lanLinks);
        Collections.sort(otherLinks);
        links.addAll(lanLinks);
        links.addAll(otherLinks);
        return new ArrayList<>(links);
    }

    private record Role(String name, String team, String description, List<String> notes, boolean secretWolf) {}
    private record NightStep(String title, String instruction, List<String> roleNames, List<String> actionTypes) {}
    private record Mode(String id, String name, String note, List<Integer> supportedPlayerCounts, List<Role> baseRoles, List<NightStep> nightSteps) {}
    private record PlayerCard(int number, Role role) {}
    private record ActionChoice(String type, String label, int targetCount, boolean optional) {}
    private record NightAction(int actorNumber, String actorRole, String actionType, List<Integer> targets, String extra, String result, Instant at) {}
    private record GameState(String gameId, Mode mode, int playerCount, Map<Integer, PlayerCard> assignments, List<NightAction> actions, int currentStepIndex, Instant createdAt) {
        static GameState empty() { return new GameState(null, null, 0, Map.of(), List.of(), 0, null); }
        boolean started() { return gameId != null; }
    }
}
