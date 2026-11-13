package com.example.pvp.combat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 掷骰器：随机源可配置种子，便于复现与测试。
 *
 * <p>通过 {@code combat.seed}（环境变量 COMBAT_SEED）指定种子时，同一进程内的骰序完全可复现；
 * 不指定则使用无种子的 {@link Random}。</p>
 */
@Component
public class DiceRoller {

    private static final Logger log = LoggerFactory.getLogger(DiceRoller.class);

    private final Random random;

    public DiceRoller(@Value("${combat.seed:}") String seedText) {
        if (seedText == null || seedText.isBlank()) {
            this.random = new Random();
            log.info("掷骰器初始化：未指定种子，使用随机种子");
        } else {
            long seed = Long.parseLong(seedText.trim());
            this.random = new Random(seed);
            log.info("掷骰器初始化：使用固定种子 seed={}（结果可复现）", seed);
        }
    }

    /**
     * 掷一颗指定面数的骰子。
     *
     * @param sides 面数
     * @return 1~sides 之间的点数
     */
    public int roll(int sides) {
        return random.nextInt(sides) + 1;
    }

    /**
     * 按骰子规格掷一次，返回逐颗点数与总和。
     *
     * @param dice 骰子规格
     * @return 本次掷骰结果
     */
    public Roll roll(Dice dice) {
        List<Integer> rolls = new ArrayList<>(dice.count());
        int total = 0;
        for (int i = 0; i < dice.count(); i++) {
            int value = roll(dice.sides());
            rolls.add(value);
            total += value;
        }
        return new Roll(dice, rolls, total);
    }

    /**
     * 一次掷骰的审计结果。
     *
     * @param dice  骰子规格
     * @param rolls 逐颗点数
     * @param total 总点数
     */
    public record Roll(Dice dice, List<Integer> rolls, int total) {
    }
}
