package org.example;

import org.um.feri.ears.algorithms.moo.nsga2.D_NSGAII;
import org.um.feri.ears.problems.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

interface Knob {
    int configCount();
    int repair(int index);
    int randomize();
}

interface Evaluator {
    double[] eval(int[] conf);
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
        return Math.max(0, Math.min(index, configCount - 1));
    }

    @Override
    public int randomize() {
        return ThreadLocalRandom.current().nextInt(0, configCount);
    }
}

class Completed extends Exception {
    public Completed() {
        super(" Optimization completted");
    }
}

class ForceStop extends RuntimeException {
    public ForceStop() {
        super("Stop evaluation");
    }
}

final class CompatNumberProblem extends NumberProblem<Double> {
    private final Knob[] knobs;
    private final Evaluator evaluator;

    public CompatNumberProblem(String name, Knob[] knobs, Evaluator evaluator, int numberOfObjectives) {
        super(name, knobs.length, 1, numberOfObjectives, 0);
        this.knobs = knobs;
        this.evaluator = evaluator;
        lowerLimit = new ArrayList<>();
        upperLimit = new ArrayList<>();
        for (Knob knob : knobs) {
            lowerLimit.add(0.0);
            upperLimit.add((double)knob.configCount());
        }
    }

    @Override
    public void evaluate(NumberSolution<Double> solution) {
        solution.setObjectives(evaluator.eval(solution.getVariables().stream().mapToInt(Double::intValue).toArray()));
    }

    @Override
    public void makeFeasible(NumberSolution<Double> solution) {
        for (int i = 0; i < knobs.length; i++) {
            solution.setValue(i, (double)knobs[i].repair(solution.getValue(i).intValue()));
        }
    }

    @Override
    public boolean isFeasible(NumberSolution<Double> solution) {
        for (int i = 0; i < knobs.length; i++) {
            final var x = solution.getValue(i);
            if (!(0 <= x && x < knobs[i].configCount())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public NumberSolution<Double> getRandomSolution() {
        final var solution = new ArrayList<Double>();
        for (Knob knob : knobs) {
            solution.add((double)knob.randomize());
        }
        return new NumberSolution<>(numberOfObjectives, solution);
    }
}

final class Adapter {

    private final Thread thread;

    private BlockingQueue<int[]> blockConfiguration;

    private BlockingQueue<double[]> blockObjectives;

    public Adapter(Function<Evaluator, Runnable> task) {
        Thread mainThread = Thread.currentThread();
        this.thread = new Thread(() -> {
            try {
                task.apply(new Hook()).run();
            } catch (ForceStop ignore) {}
            mainThread.interrupt();
        });
    }

    public void start() {
        blockConfiguration = new LinkedBlockingQueue<>(1);
        blockObjectives = new LinkedBlockingQueue<>(1);
        thread.start();
    }

    public void stop() {
        thread.interrupt();
    }

    public int[] nextConfiguration() throws Completed  {
        try {
            return blockConfiguration.take();
        } catch (InterruptedException e) {
            throw new Completed();
        }
    }

    public void update(double[] meas) {
        blockObjectives.add(meas);
    }

    private class Hook implements Evaluator {

        public double[] eval(int[] conf) {
            blockConfiguration.add(conf);
            try {
                return blockObjectives.take();
            } catch (InterruptedException e) {
                throw new ForceStop();
            }
        }
    }
}

class Wrapper {
    Adapter adapter;
    Function<int[], double[]> function;

    Wrapper(Adapter adapter, Function<int[], double[]> function) {
        this.adapter = adapter;
        this.function = function;
    }

    double[] get() throws Completed {
        final var conf = adapter.nextConfiguration();

        // measure
        final var result = function.apply(conf);

        // but since we don't have anything to measure optimzie the result
        adapter.update(result);

        return result;
    }
}

public class Main {

    public static double[] eval(int[] conf) {
        System.out.println(Arrays.toString(conf));
        final var sum = Arrays.stream(conf).sum();
        final var max = Arrays.stream(conf).max();
        return new double[]{sum, -max.getAsInt()};
    }

    public static void main(String[] args) {

        Knob[] knobs = new Knob[]{
                new RawKnob(100),
                new RawKnob(100),
                new RawKnob(100),
                new RawKnob(100)
        };

        Adapter adapter = new Adapter(h ->
                () -> {
                    try {
                        new D_NSGAII().execute(new Task<>(new CompatNumberProblem("problem", knobs, h, 2), StopCriterion.EVALUATIONS, 100000, 0, 0));
                    } catch (StopCriterionException ignored) {}
                });
        adapter.start();
        Wrapper wrapper = new Wrapper(adapter, Main::eval);

        try {
            for (int i = 0; i < 1000; i++) {
                System.out.println(Arrays.toString(wrapper.get()));
            }
            adapter.stop();
        } catch (Completed e) {
            System.out.println("Optimization completed");

        }
    }
}
