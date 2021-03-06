package com.mrkirby153.snowsgivingbot.commands;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.mrkirby153.botcore.command.Command;
import com.mrkirby153.botcore.command.CommandException;
import com.mrkirby153.botcore.command.Context;
import com.mrkirby153.botcore.command.args.CommandContext;
import com.mrkirby153.snowsgivingbot.entity.GiveawayEntity;
import com.mrkirby153.snowsgivingbot.entity.repo.GiveawayRepository;
import com.mrkirby153.snowsgivingbot.services.DiscordService;
import com.mrkirby153.snowsgivingbot.services.RedisQueueService;
import com.mrkirby153.snowsgivingbot.services.StandaloneWorkerService;
import com.mrkirby153.snowsgivingbot.services.backfill.BackfillTask;
import com.mrkirby153.snowsgivingbot.services.backfill.GiveawayBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.mrkirby153.kcutils.Time;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCommands {

    private final GiveawayBackfillService backfillService;
    private final DiscordService discordService;
    private final GiveawayRepository giveawayRepository;
    private final RedisQueueService redisQueueService;
    private final StandaloneWorkerService standaloneWorkerService;
    private final ShardManager shardManager;
    private final RedisTemplate<String, String> template;

    @Command(name = "ping", clearance = 100)
    public void ping(Context context, CommandContext cmdContext) {
        long start = System.currentTimeMillis();
        context.getChannel().sendTyping().queue(v -> {
            context.getChannel()
                .sendMessage("Pong! " + Time.INSTANCE.format(1, System.currentTimeMillis() - start))
                .queue();
        });
    }

    @Command(name = "log-level", arguments = {"<logger:string>", "[level:string]"}, clearance = 101)
    public void logLevel(Context context, CommandContext cmdContext) {
        String loggerName = cmdContext.get("logger");
        String level = cmdContext.get("level");

        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);

        if (level != null) {
            try {
                Level prev = logger.getEffectiveLevel();
                Level lvl = Level.valueOf(level.toUpperCase());
                logger.setLevel(lvl);
                log.info("Log level changed from {} -> {} for [{}]", prev, lvl, loggerName);
            } catch (IllegalArgumentException e) {
                throw new CommandException("Log level not recognized");
            }

            context.getChannel().sendMessage(
                "Logger `" + loggerName + "` has been set to level `" + level + "`").queue();
        } else {
            context.getChannel().sendMessage(
                "Logger `" + loggerName + "` is at level `" + logger.getEffectiveLevel().toString()
                    + "`").queue();
        }
    }

    @Command(name = "backfill", arguments = {"<id:int>"}, clearance = 101)
    public void startBackfill(Context context, CommandContext cmdContext) {
        GiveawayEntity entity = giveawayRepository
            .findById(cmdContext.<Integer>getNotNull("id").longValue())
            .orElseThrow(() -> new CommandException("Giveaway not found!"));
        if (backfillService.getRunningGiveawayIDs().contains(entity.getId())) {
            throw new CommandException("Backfill is already in progress!");
        }
        BackfillTask task = backfillService.startBackfill(entity);
        task.getFuture().handle((count, throwable) -> {
            if (throwable != null) {
                context.getChannel().sendMessage(
                    "Backfill did not complete successfully for " + entity.getName() + ": "
                        + throwable.getMessage()).queue();
            } else {
                context.getChannel()
                    .sendMessage(
                        "Backfill of " + entity.getName() + " succeeded in " + Time.INSTANCE
                            .format(1, task.getTimeTaken()) + ": (" + count + ")")
                    .queue();
            }
            return null;
        });
        context.getChannel().sendMessage(
            "Starting backfill of " + entity.getName() + " (" + task.getId()
                + "). This may take a _long_ time").queue();
    }

    @Command(name = "status", parent = "backfill", clearance = 101)
    public void status(Context context, CommandContext cmdContext) {
        StringBuilder sb = new StringBuilder();
        backfillService.getRunning().forEach(task -> sb
            .append(String.format(" - %d: %d (%d processed)\n", task.getId(), task.getGiveawayId(),
                task.getProcessed())));
        if (sb.length() == 0) {
            sb.append("No tasks running");
        }
        context.getChannel().sendMessage(sb.toString()).queue();
    }

    @Command(name = "cancel", parent = "backfill", clearance = 101, arguments = {"<id:int>"})
    public void cancel(Context context, CommandContext cmdContext) {
        BackfillTask task = backfillService.getRunning().stream()
            .filter(t -> t.getId() == cmdContext.<Integer>getNotNull("id")).findFirst()
            .orElseThrow(() -> new CommandException("Backfill task not running"));
        task.cancel();
        context.getChannel().sendMessage("Canceled task!").queue();
    }

    @Command(name = "cache", clearance = 101)
    public void cacheStats(Context context, CommandContext cmdContext) {
        StringBuilder sb = new StringBuilder();
        redisQueueService.allQueues().forEach((queue, size) -> sb.append(" - ").append(queue)
            .append(": ").append(size).append("\n"));
        if (sb.length() == 0) {
            sb.append("All queues are empty!");
        }
        context.getChannel().sendMessage(sb.toString()).queue();
    }

    @Command(name = "workers", clearance = 101, arguments = {"<sleep:int>", "<batch:int>"})
    public void workerSettings(Context context, CommandContext cmdContext) {
        int batch = cmdContext.getNotNull("batch");
        int sleep = cmdContext.getNotNull("sleep");
        redisQueueService.updateWorkers(sleep, batch);
        context.getChannel().sendMessage("Updated!").queue();
    }

    @Command(name = "tasks", clearance = 101, arguments = {"<tasks:int>"})
    public void tasks(Context context, CommandContext cmdContext) {
        int count = cmdContext.getNotNull("tasks");
        redisQueueService.updateWorkerCount(count);
        context.getChannel().sendMessage("Updated!").queue();
    }

    @Command(name = "get", clearance = 101, arguments = {"<guild:string>"}, parent = "standalone")
    public void getStandalone(Context context, CommandContext commandContext) {
        String guild = commandContext.getNotNull("guild");
        Guild g = shardManager.getGuildById(guild);
        if (g == null) {
            throw new CommandException("Guild not found!");
        }
        if (standaloneWorkerService.isStandalone(g)) {
            context.getChannel().sendMessage("Guild " + g.getName() + " is standalone").queue();
        } else {
            context.getChannel().sendMessage("Guild " + g.getName() + " is not standalone").queue();

        }
    }

    @Command(name = "set", clearance = 101, arguments = {"<guild:string>",
        "<enable:boolean>"}, parent = "standalone")
    public void setStandalone(Context context, CommandContext commandContext) {
        String guild = commandContext.getNotNull("guild");
        boolean standalone = commandContext.getNotNull("enable");
        Guild g = shardManager.getGuildById(guild);
        if (g == null) {
            throw new CommandException("Guild not found!");
        }
        if (standalone) {
            standaloneWorkerService.enableStandaloneWorker(g);
            context.getChannel().sendMessage("Enabled standalone for " + g.getName()).queue();
        } else {
            standaloneWorkerService.disableStandaloneWorker(g);
            context.getChannel().sendMessage("Disabled standalone for " + g.getName()).queue();
        }
    }

    @Command(name = "status", clearance = 101)
    public void getHeartbeat(Context context, CommandContext commandContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Worker Load**\n");
        Map<String, Double> workerLoad = standaloneWorkerService.getWorkerLoad();
        if (workerLoad.size() == 0) {
            sb.append("No workers reported load\n");
        } else {
            workerLoad
                .forEach((id, load) -> sb.append(String.format(" %s - %s\n", id, load)));
        }
        sb.append("**Worker Heartbeats**\n");
        Map<String, Long> workerHeartbeats = standaloneWorkerService.getWorkerHeartbeats();
        if (workerHeartbeats.size() == 0) {
            sb.append("No workers reported heartbeats recently\n");
        } else {
            workerHeartbeats.forEach((id, heartbeat) -> {
                String diff = Time.INSTANCE.format(1, System.currentTimeMillis() - heartbeat);
                id = id.split(":")[1];
                sb.append(String.format(" %s: %s ago\n", id, diff));
            });
        }
        discordService.sendLongMessage(context.getChannel(), sb.toString());
    }
}
