/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.minestom.context;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import me.lucko.luckperms.common.context.ImmutableContextSetImpl;
import me.lucko.luckperms.minestom.LPMinestomPlugin;
import me.lucko.luckperms.minestom.context.defaults.DimensionTypeContextProvider;
import me.lucko.luckperms.minestom.context.defaults.GameModeContextProvider;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

public final class MinestomPlayerCalculator implements ContextCalculator<Player> {

    private final @NonNull Set<ContextProvider> providers;

    public MinestomPlayerCalculator(@NotNull LPMinestomPlugin plugin, @NotNull EventNode<Event> eventNode, @NotNull Set<ContextProvider> providers, @NotNull Set<String> disabled) {
        providers = new HashSet<>(providers);

        // register the default providers
        providers.add(new GameModeContextProvider());
        providers.add(new DimensionTypeContextProvider());

        this.providers = providers.stream()
                .filter(p -> !disabled.contains(p.key()))
                .peek(p -> p.register(player -> plugin.getContextManager().signalContextUpdate(player), eventNode))
                .collect(Collectors.toSet());
    }

    @Override
    public void calculate(@NonNull Player subject, @NonNull ContextConsumer consumer) {
        this.providers.forEach(p -> p.query(subject).ifPresent(value -> consumer.accept(p.key(), value)));
    }

    @Override
    public @NonNull ContextSet estimatePotentialContexts() {
        ImmutableContextSet.Builder builder = new ImmutableContextSetImpl.BuilderImpl();
        this.providers.forEach(p -> p.potentialValues().forEach(value -> builder.add(p.key(), value)));
        return builder.build();
    }

}
