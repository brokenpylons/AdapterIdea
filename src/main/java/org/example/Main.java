package org.example;

import org.um.feri.ears.algorithms.moo.nsga3.I_NSGAIII;
import org.um.feri.ears.problems.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.concurrent.ThreadLocalRandom;

interface Knob {
    int configCount();
    int repair(int index);
    int randomize();
}

interface Evaluator {
    double[] eval(List<Integer> conf);
}

class RawKnob implements Knob {

    private final int configCount;

    public RawKnob(int configCount) {
        this.configCount = configCount;
    }

    @Override
    public int configCount() {
        return configCount;
    }

    @Override
    public int repair(int index) {
        return Math.max(0, Math.max(index, configCount));
    }

    @Override
    public int randomize() {
        return ThreadLocalRandom.current().nextInt(0, configCount);
    }
}

final class CompatNumberProblem extends NumberProblem<Integer> {
    private final Knob[] knobs;
    private final Evaluator evaluator;

    public CompatNumberProblem(String name, Knob[] knobs, Evaluator evaluator, int numberOfObjectives) {
        super(name, knobs.length, 1, numberOfObjectives, 0);
        this.knobs = knobs;
        this.evaluator = evaluator;
        lowerLimit = new ArrayList<>();
        upperLimit = new ArrayList<>();
        for (Knob knob : knobs) {
            lowerLimit.add(0);
            upperLimit.add(knob.configCount());
        }
    }

    @Override
    public void evaluate(NumberSolution<Integer> solution) {
        solution.setObjectives(evaluator.eval(solution.getVariables()));
    }

    @Override
    public void makeFeasible(NumberSolution<Integer> solution) {
        for (int i = 0; i < knobs.length; i++) {
            solution.setValue(i, knobs[i].repair(solution.getValue(i)));
        }
    }

    @Override
    public boolean isFeasible(NumberSolution<Integer> solution) {
        for (int i = 0; i < knobs.length; i++) {
            final var x = solution.getValue(i);
            if (!(0 <= x && x < knobs[i].configCount())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public NumberSolution<Integer> getRandomSolution() {
        final var solution = new ArrayList<Integer>();
        for (Knob knob : knobs) {
            solution.add(knob.randomize());
        }
        return new NumberSolution<>(numberOfObjectives, solution);
    }
}

final class Adapter {

    private final Thread thread;

    private BlockingQueue<List<Integer>> blockConfiguration;

    private BlockingQueue<double[]> blockObjectives;

    public Adapter(Function<Evaluator, Runnable> task) {
        this.thread = new Thread(task.apply(new Hook()));
    }

    public void start() {
        blockConfiguration = new LinkedBlockingQueue<>(1);
        blockObjectives = new LinkedBlockingQueue<>(1);
        thread.start();
    }

    public List<Integer> nextConfiguration() {
        try {
            return blockConfiguration.take();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void update(double[] meas) {
        blockObjectives.add(meas);
    }

    private class Hook implements Evaluator {
        public double[] eval(List<Integer> conf) {
            blockConfiguration.add(conf);
            try {
                return blockObjectives.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

public class Main {

    public static void main(String[] args) {

        Knob[] knobs = new Knob[]{
                new RawKnob(10),
                new RawKnob(10),
                new RawKnob(10),
                new RawKnob(10)
        };

        Adapter adapter = new Adapter(h ->
                () -> {
                    try {
                        new I_NSGAIII().execute(new Task<>(new CompatNumberProblem("problem", knobs, h, 2), StopCriterion.STAGNATION, 0, 0, 0));
                    } catch (StopCriterionException e) {
                        throw new RuntimeException(e);
                    }
                });
        adapter.start();
        for (int i = 0; i < 1000; i++) {
            System.out.println(adapter.nextConfiguration());
            adapter.update(new double[]{ ThreadLocalRandom.current().nextDouble(), ThreadLocalRandom.current().nextDouble() });
        }
    }
}