/*
 * Copyright 2017 Paul Gestwicki, Alex Hoffman, and Darby Siscoe
 *
 * This file is part of Fermata
 *
 * Fermata is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Fermata is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Fermata.  If not, see <http://www.gnu.org/licenses/>.
 */

package edu.bsu.ggj17.core;

import com.google.common.collect.Lists;
import playn.core.*;
import playn.scene.ImageLayer;
import pythagoras.f.IDimension;
import pythagoras.f.MathUtil;
import pythagoras.f.Rectangle;
import react.*;
import tripleplay.game.ScreenStack;
import tripleplay.ui.*;
import tripleplay.ui.layout.AbsoluteLayout;
import tripleplay.ui.layout.AxisLayout;
import tripleplay.ui.util.BoxPoint;
import tripleplay.util.Colors;

import java.util.List;

public class GameScreen extends ScreenStack.UIScreen implements Updateable {

    private static final int MAX_ELAPSED_TIME_WITHOUT_DEATH = 500;

    public final Signal<EndOption> done = Signal.create();
    private final FlappyPitchGame game;
    private final Value<Float> startingPitch = Value.create(null);
    private PlayerSprite playerSprite;
    private final List<AbstractObstacleSprite> obstacles = Lists.newArrayList();
    private final Value<Integer> score = Value.create(0);

    private float topPitch;
    private float bottomPitch;
    private State state;

    public GameScreen(final FlappyPitchGame game) {
        super(game.plat);
        this.game = game;

        makeDefaultBackground();
        if (game.debugMode) {
            makeDebugHUD();
        }
        makeHud();
        configurePlayerSprite();
        configureStarBar();

        setState(countdownState);
        game.pitch.connect(new Slot<Float>() {
            @Override
            public void onEmit(Float newPitch) {
                state.pitchChanged(newPitch);
            }
        });
    }


    private void makeDefaultBackground() {
        Image bgImage = game.plat.assets().getImage("images/bg.png");
        ImageLayer bgLayer = new ImageLayer(bgImage);
        bgLayer.setSize(game.plat.graphics().viewSize);
        layer.add(bgLayer);
    }

    private void configurePlayerSprite() {
        playerSprite = new PlayerSprite(game);
        layer.addAt(playerSprite.layer, 75, game.plat.graphics().viewSize.height() / 2);
    }

    private void configureStarBar() {
        StartBarSprite startBarSprite = new StartBarSprite(game.plat.assets());
        obstacles.add(startBarSprite);
        layer.addAt(startBarSprite.layer, 30, game.plat.graphics().viewSize.height() /2);
    }

    private void setState(State newState) {
        if (this.state != null) {
            this.state.onExit();
        }
        this.state = newState;
        this.state.onEnter();
    }

    private void makeDebugHUD() {
        Root root = iface.createRoot(new AbsoluteLayout(), GameStyles.newSheet(game.plat.graphics()), layer)
                .setSize(game.plat.graphics().viewSize);
        root.add(new Group(AxisLayout.vertical())
                .add(new PitchLabel(),
                        new StartingPitchLabel())
                .setConstraint(AbsoluteLayout.uniform(BoxPoint.BR)));
    }

    private void makeHud() {
        Root root = iface.createRoot(new AbsoluteLayout(), GameStyles.newSheet(game.plat.graphics()), layer)
                .setSize(game.plat.graphics().viewSize);
        root.add(new ScoreLabel()
                .setConstraint(AbsoluteLayout.uniform(BoxPoint.TL))
                .addStyles(Style.BACKGROUND.is(Background.solid(Colors.WHITE).inset(3, 12, 3, 3))));
    }

    @Override
    public void update(int deltaMS) {
        this.state.update(deltaMS);
    }

    @Override
    public Game game() {
        return game;
    }

    private interface State extends Updateable {
        void onEnter();

        void onExit();

        void pitchChanged(Float newPitch);
    }

    private abstract class AbstractState implements State {
        @Override
        public void onExit() {
            // Do nothing
        }

        @Override
        public void onEnter() {
            // Do nothing
        }

        @Override
        public void update(int deltaMS) {
            // Do nothing
        }

        @Override
        public void pitchChanged(Float newPitch) {
            // Do nothing
        }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final AbstractState countdownState = new AbstractState() {
        private Root startingMessageHud;
        private Label countdownLabel = new Label("Get Ready...");
        private int timeRemaining;

        @Override
        public void update(int deltaMS) {
            timeRemaining -= deltaMS;
            if (timeRemaining <= 0) {
                setState(new StartPitchState());
            }
        }

        @Override
        public void onEnter() {
            timeRemaining = 1500;
            if (startingMessageHud == null) {
                startingMessageHud = iface.createRoot(new AbsoluteLayout(), GameStyles.newSheet(game.plat.graphics()), layer)
                        .setSize(game.plat.graphics().viewSize);
                startingMessageHud.add(countdownLabel.setConstraint(AbsoluteLayout.uniform(BoxPoint.CENTER)));
            }
        }

        @Override
        public void onExit() {
            startingMessageHud.setVisible(false);
        }

        @Override
        public String toString() {
            return "Countdown state";
        }
    };

    private final class StartPitchState extends AbstractState {
        private Root startingPitchHud;

        @Override
        public void onEnter() {
            if (startingPitchHud == null) {
                startingPitchHud = iface.createRoot(new AbsoluteLayout(), GameStyles.newSheet(game.plat.graphics()), layer)
                        .setSize(game.plat.graphics().viewSize);
                startingPitchHud.add(new Label("Make a sound!").setConstraint(AbsoluteLayout.uniform(BoxPoint.CENTER)));
            }
            startingPitch.update(null);
        }

        @Override
        public void pitchChanged(Float newPitch) {
            if (newPitch != null) {
                topPitch = newPitch + 100;
                bottomPitch = newPitch - 100;
                setState(new PlayingState());
            }
        }

        @Override
        public void onExit() {
            startingPitchHud.setVisible(false);
        }
    }

    private final class PlayingState extends AbstractState {
        private final Rectangle playerRect = new Rectangle();
        private final Rectangle otherRect = new Rectangle();
        private final List<AbstractObstacleSprite> toRemove = Lists.newArrayList();
        private final ObstacleGenerator generator = new ObstacleGenerator(game.plat, layer);
        private boolean shouldAnimateOnNextChange = true;
        private int elapsedTimeWithoutPitch;

        PlayingState() {
            generator.onGenerate.connect(new Slot<AbstractObstacleSprite>() {
                @Override
                public void onEmit(AbstractObstacleSprite sprite) {
                    obstacles.add(sprite);
                }
            });
        }

        @Override
        public void update(int deltaMS) {
            generator.update(deltaMS);
            score.update(score.get() + deltaMS);

            elapsedTimeWithoutPitch += deltaMS;
            if (elapsedTimeWithoutPitch >= MAX_ELAPSED_TIME_WITHOUT_DEATH && !game.immortal) {
                setState(new DeathState());
                return;
            }
            playerRect.setBounds(playerSprite.layer.tx(), playerSprite.layer.ty(), playerSprite.layer.width(),
                    playerSprite.layer.height());
            for (AbstractObstacleSprite obstacle : obstacles) {
                obstacle.update(deltaMS);
                if (obstacle.layer.tx() + obstacle.layer.width() < 0) {
                    toRemove.add(obstacle);
                }
                otherRect.setBounds(obstacle.layer.tx(), obstacle.layer.ty(), obstacle.layer.width(),
                        obstacle.layer.height());
                if (playerRect.intersects(otherRect)) {
                    if (obstacle.isDeadly()) {
                        setState(new DeathState());
                    } else {
                        toRemove.add(obstacle);
                        setState(new GraceState(this));
                    }

                }
            }
            while (!toRemove.isEmpty()) {
                AbstractObstacleSprite sprite = toRemove.remove(0);
                obstacles.remove(sprite);
                layer.remove(sprite.layer);
            }

        }

        @Override
        public void pitchChanged(Float newPitch) {
            if (newPitch != null) {
                elapsedTimeWithoutPitch = 0;
            } else {
                return;
            }

            if (shouldAnimateOnNextChange) {
                shouldAnimateOnNextChange = false;

                float screenHeight = game.plat.graphics().viewSize.height();
                float pitchWidth = topPitch - bottomPitch;
                float pitchPercent = clamp((newPitch - bottomPitch) / pitchWidth);
                float newY = screenHeight - (screenHeight * pitchPercent);

                iface.anim.tweenY(playerSprite.layer)
                        .to(newY)
                        .in(200)
                        .then()
                        .action(new Runnable() {
                            @Override
                            public void run() {
                                shouldAnimateOnNextChange = true;
                            }
                        });
            }
        }

        private float clamp(float value) {
            return Math.max(0, Math.min(1.0f, value));
        }
    }

    private final class DeathState extends AbstractState {

        private Root root;

        @Override
        public void onEnter() {
            if (root == null) {
                root = iface.createRoot(AxisLayout.vertical(), GameStyles.newSheet(game.plat.graphics()), layer)
                        .setSize(game.plat.graphics().viewSize);
                Group group = new Group(AxisLayout.vertical());
                group.setStyles(Style.BACKGROUND.is(Background.composite(
                        Background.roundRect(game.plat.graphics(), Colors.WHITE, 6, Colors.BLACK, 0.2f)
                                .inset(12, 12))));
                group.add(new Label("Now you must repeat!"), new Button("Play again").onClick(new Slot<Button>() {
                    @Override
                    public void onEmit(Button button) {
                        done.emit(EndOption.PLAY_AGAIN);
                    }
                }));
                group.add(new Button("Main Menu").onClick(new UnitSlot() {
                    @Override
                    public void onEmit() {
                        done.emit(EndOption.MAIN_MENU);
                    }
                }));
                root.add(group);
            }
        }


    }

    private final class GraceState extends AbstractState {

        private final State previous;
        private ImageLayer breatheLayer;

        GraceState(State previous) {
            this.previous = previous;

            TextLayout layout = game.plat.graphics().layoutText("Breathe!", new TextFormat(
                    new Font("Bold", 48f)));
            Canvas canvas = game.plat.graphics().createCanvas(200, 50);
            canvas.fillText(layout, 0, 0);
            breatheLayer = new ImageLayer(canvas.image);
        }

        @Override
        public void onEnter() {
            IDimension size = game.plat.graphics().viewSize;
            breatheLayer.setAlpha(0);
            layer.addCenterAt(breatheLayer, size.width() / 2, size.height() / 2);
            iface.anim.tweenAlpha(breatheLayer)
                    .to(1)
                    .in(500)
                    .easeIn()
                    .then()
                    .tweenAlpha(breatheLayer)
                    .to(0)
                    .in(500)
                    .easeOut();


            iface.anim.tweenRotation(playerSprite.layer)
                    .from(0)
                    .to(MathUtil.TWO_PI)
                    .in(1000)
                    .then()
                    .action(new Runnable() {
                        @Override
                        public void run() {
                            setState(previous);
                        }
                    });
        }

        @Override
        public void onExit() {
            layer.remove(breatheLayer);
        }
    }

    private final class PitchLabel extends Label {
        PitchLabel() {
            game.pitch.connect(new Slot<Float>() {
                @Override
                public void onEmit(Float aFloat) {
                    if (aFloat != null) {
                        setText("Pitch: " + String.format("%2f", aFloat));
                    } else {
                        setText("--");
                    }
                }
            });
        }
    }

    private final class StartingPitchLabel extends Label {
        private Connection connection;

        StartingPitchLabel() {
            connection = game.pitch.connect(new Slot<Float>() {
                @Override
                public void onEmit(Float aFloat) {
                    if (aFloat != null) {
                        setText("Starting: " + String.format("%2f", aFloat));
                        connection.close();
                    }
                }
            });
        }
    }

    private final class ScoreLabel extends Label {
        ScoreLabel() {
            super("Score: 0");
            score.connect(new Slot<Integer>() {
                @Override
                public void onEmit(Integer integer) {
                    setText("Score: " + (integer / 100));
                }
            });
        }
    }
}
