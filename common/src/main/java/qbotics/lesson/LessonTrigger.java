package qbotics.lesson;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.criterion.ContextAwarePredicate;
import net.minecraft.advancements.criterion.EntityPredicate;
import net.minecraft.advancements.criterion.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * The advancement trigger "qbotics:lesson": met when a player's program does one of the things
 * a lesson asks ({@link Lessons}). Each criterion names one step, as
 * {@code {"trigger": "qbotics:lesson", "conditions": {"lesson": "move"}}}.
 */
public final class LessonTrigger extends SimpleCriterionTrigger<LessonTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, String lesson) {
        this.trigger(player, instance -> instance.lesson().equals(lesson));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, String lesson) implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Codec.STRING.fieldOf("lesson").forGetter(TriggerInstance::lesson)
        ).apply(i, TriggerInstance::new));
    }
}
