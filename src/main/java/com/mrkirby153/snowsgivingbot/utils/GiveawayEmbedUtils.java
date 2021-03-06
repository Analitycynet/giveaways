package com.mrkirby153.snowsgivingbot.utils;

import com.mrkirby153.snowsgivingbot.entity.GiveawayEntity;
import me.mrkirby153.kcutils.Time;
import me.mrkirby153.kcutils.Time.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Message;

import java.awt.Color;
import java.util.Arrays;
import java.util.stream.Collectors;

public class GiveawayEmbedUtils {

    public static Message renderMessage(GiveawayEntity entity) {
        MessageBuilder mb = new MessageBuilder();
        EmbedBuilder eb = new EmbedBuilder();

        eb.setTitle(entity.getName());
        eb.setTimestamp(entity.getEndsAt().toInstant());

        switch (entity.getState()) {
            case RUNNING:
                long timeLeftMs = entity.getEndsAt().getTime() - System.currentTimeMillis();
                String time = Time.INSTANCE
                    .formatLong(timeLeftMs, TimeUnit.SECONDS);
                eb.setColor(Color.GREEN);
                eb.setDescription(
                    "Click the reaction below to enter! \n\nTime Left **" + (timeLeftMs < 1000
                        ? "< 1 Second" : time) + "**");
                eb.setFooter(entity.getWinners() + " winners | Ends at");
                break;
            case ENDED:
                eb.setColor(Color.RED);
                if (entity.getFinalWinners().isEmpty()) {
                    eb.setDescription("Giveaway has ended!\n\nCould not determine a winner :(");
                } else {
                    if (!entity.isSecret()) {
                        String winners = Arrays.stream(entity.getFinalWinners().split(","))
                            .map(a -> "<@!" + a.trim() + ">").collect(Collectors.joining(" "));
                        if (winners.length() > 1900) {
                            eb.setDescription("Giveaway has ended!");
                        } else {
                            eb.setDescription(
                                "Giveaway has ended!\n\n**Winners:** " + winners);
                        }
                    } else {
                        eb.setDescription("Giveaway has ended!\n\nWinners will be announced soon!");
                    }
                }
                eb.setFooter(entity.getWinners() + " winners | Ended at");
                break;
            case ENDING:
                eb.setColor(Color.RED);
                eb.setDescription("Giveaway has ended! Determining winners");
                eb.setFooter(entity.getWinners() + " winners | Ended at");
                break;
        }

        mb.setEmbed(eb.build());
        mb.build();
        return mb.build();
    }
}
