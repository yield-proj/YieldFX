/*
 * Copyright [2022] [Xebisco]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xebisco.yieldfx;

import com.xebisco.yield.*;
import com.xebisco.yield.config.WindowConfiguration;
import com.xebisco.yield.render.RenderMaster;
import com.xebisco.yield.render.Renderable;
import com.xebisco.yield.render.RenderableType;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Affine;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class YieldFX extends Application implements RenderMaster {

    private Set<Renderable> renderables;
    private Set<Integer> pressing = new HashSet<>();
    private Stage stage;
    private final Canvas canvas = new Canvas(1280, 720);
    private Map<String, Font> fonts = new HashMap<>();
    private Group root;
    private int mouseX, mouseY;
    private YldTask threadTask;
    private Scene scene;

    private final Map<Integer, MediaPlayer> players = new HashMap<>();
    private final Map<Integer, Integer> playersLoop = new HashMap<>();

    private Affine affinetransform = new Affine();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Yld.getDebugLogger().log("YieldFX: Launching application on stage '" + stage + "'");
        scene = new Scene(root = new Group());
        scene.setOnKeyPressed(e -> pressing.add(e.getCode().getCode()));
        scene.setOnKeyReleased(e -> pressing.remove(e.getCode().getCode()));
        scene.setOnMouseMoved(e -> {
            mouseX = (int) (e.getX() / stage.getWidth() * canvas.getWidth());
            mouseY = (int) (e.getY() / stage.getHeight() * canvas.getHeight());
        });
        scene.setOnMousePressed(e -> pressing.add(-e.getButton().ordinal()));
        scene.setOnMouseReleased(e -> pressing.remove(-e.getButton().ordinal()));
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> Yld.exit());
        root.getChildren().add(canvas);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setImageSmoothing(false);
        g.setFill(Color.RED);
        g.fillRect(0, 0, 100, 100);
        String clazz, config;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(YieldFX.class.getResourceAsStream("/launch.txt"))))) {
            clazz = reader.readLine();
            config = reader.readLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        YldGame game;
        try {
            game = (YldGame) Class.forName(clazz).getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | ClassNotFoundException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        GameConfiguration gameConfiguration = new GameConfiguration();
        RelativeFile relativeFile = new RelativeFile("config");
        relativeFile.setInputStream(Objects.requireNonNull(YieldFX.class.getResourceAsStream("/" + config)));
        Ini.file(relativeFile, gameConfiguration);
        if (gameConfiguration.undecorated)
            stage.initStyle(StageStyle.UNDECORATED);
        stage.setWidth(gameConfiguration.width);
        stage.setHeight(gameConfiguration.height);
        stage.setResizable(gameConfiguration.resizable);
        stage.setAlwaysOnTop(gameConfiguration.alwaysOnTop);
        stage.setFullScreen(gameConfiguration.fullscreen);
        stage.setTitle(gameConfiguration.title);
        gameConfiguration.renderMaster = this;
        if (gameConfiguration.runOnThisThread) {
            throw new YieldFXException("YieldFX needs to have the 'runOnThisThread' option to be false.");
        }
        new AnimationTimer() {
            @Override
            public void handle(long l) {
                canvas.setScaleX(scene.getWidth() / canvas.getWidth());
                canvas.setScaleY(scene.getHeight() / canvas.getHeight());
                canvas.setTranslateX(scene.getWidth() / 2f - initialWidth / 2f);
                canvas.setTranslateY(scene.getHeight() / 2f - initialHeight / 2f);
                if (bgColor != null) {
                    g.setFill(bgColor);
                    bgColor = null;
                    g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
                    g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                    try {
                        for (Renderable renderable : renderables) {
                            if (renderable.getType() != RenderableType.IMAGE) {
                                if (renderable.getSpecificColor() == null)
                                    renderable.setSpecificColor(toFXColor(renderable.getColor()));
                                g.setFill((Color) renderable.getSpecificColor());
                            }
                            g.setTransform(affinetransform.clone());
                            g.rotate(Math.toRadians(-renderable.getRotation()));
                            switch (renderable.getType()) {
                                case LINE:
                                    g.setLineWidth(renderable.getThickness());
                                    g.strokeLine(renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getX() + renderable.getWidth() / 2f, renderable.getY() + renderable.getHeight() / 2f);
                                    break;
                                case RECTANGLE:
                                    if (renderable.isFilled())
                                        g.fillRect(renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getWidth(), renderable.getHeight());
                                    else {
                                        g.setLineWidth(renderable.getThickness());
                                        g.strokeRect(renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getWidth(), renderable.getHeight());
                                    }
                                    break;
                                case OVAL:
                                    if (renderable.isFilled())
                                        g.fillOval(renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getWidth(), renderable.getHeight());
                                    else {
                                        g.setLineWidth(renderable.getThickness());
                                        g.strokeOval(renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getWidth(), renderable.getHeight());
                                    }
                                    break;
                                case ROUNDED_RECTANGLE:
                                    if (renderable.isFilled())
                                        g.fillRoundRect(renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getWidth(), renderable.getHeight(), renderable.getArcWidth(), renderable.getArcHeight());
                                    else {
                                        g.setLineWidth(renderable.getThickness());
                                        g.strokeRoundRect(renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getWidth(), renderable.getHeight(), renderable.getArcWidth(), renderable.getArcHeight());
                                    }
                                    break;
                                case IMAGE:
                                    g.drawImage((Image) renderable.getSpecific(), renderable.getX() - renderable.getWidth() / 2f, renderable.getY() - renderable.getHeight() / 2f, renderable.getWidth(), renderable.getHeight());
                                    break;
                                case TEXT:
                                    String[] ss = renderable.getSpecific().toString().split("\1");
                                    g.setFont(fonts.get(ss[1]));
                                    g.fillText(ss[0], renderable.getX() - getStringWidth(ss[0], ss[1]) / 2f, renderable.getY() + getStringHeight(ss[0], ss[1]) / 4f);
                                    break;
                            }
                        }
                    } catch (ConcurrentModificationException ignore) {
                    }
                    threadTask.execute();
                }
            }
        }.start();

        YldGame.launch(game, gameConfiguration);
    }

    private int initialWidth, initialHeight;

    private Color bgColor;

    public static void main(String[] args) {
        launch(args);
    }

    public static Color toFXColor(com.xebisco.yield.Color color) {
        return new Color(color.getR(), color.getG(), color.getB(), color.getA());
    }

    @Override
    public void start(Set<Renderable> renderables) {
        this.renderables = renderables;
    }

    @Override
    public SampleWindow initWindow(WindowConfiguration windowConfiguration) {
        stage.getIcons().add((Image) windowConfiguration.icon.getSpecificImage());
        stage.show();
        stage.setWidth(windowConfiguration.width + (stage.getWidth() - scene.getWidth()));
        stage.setHeight(windowConfiguration.height + (stage.getHeight() - scene.getHeight()));
        return new SampleWindow() {
            @Override
            public int getWidth() {
                return (int) stage.getWidth();
            }

            @Override
            public int getHeight() {
                return (int) stage.getHeight();
            }
        };
    }

    @Override
    public void frameEnd(com.xebisco.yield.Color color, int i, int i1, int i2, int i3, float v, float v1) {
        bgColor = toFXColor(color);
    }

    @Override
    public void onResize(int i, int i1) {
        Yld.getDebugLogger().log("YieldFX: Resized to " + i + "x" + i1);
        canvas.setWidth(i);
        canvas.setHeight(i1);
        canvas.resize(i, i1);
        initialWidth = i;
        initialHeight = i1;
    }

    @Override
    public boolean canStart() {
        return true;
    }

    @Override
    public float fpsCount() {
        return 0;
    }

    @Override
    public Set<Integer> pressing() {
        return pressing;
    }

    @Override
    public int mouseX() {
        return mouseX;
    }

    @Override
    public int mouseY() {
        return mouseY;
    }

    @Override
    public void setThreadTask(YldTask threadTask) {
        this.threadTask = threadTask;
    }

    @Override
    public float getStringWidth(String s, String s1) {
        javafx.scene.text.Text text = new Text(s);
        text.setFont(fonts.get(s1));
        return (float) text.getBoundsInLocal().getWidth();
    }

    @Override
    public float getStringHeight(String s, String s1) {
        javafx.scene.text.Text text = new Text(s);
        text.setFont(fonts.get(s1));
        return (float) text.getBoundsInLocal().getHeight();
    }

    @Override
    public void loadAudioClip(AudioClip audioClip, AudioPlayer audioPlayer) {
        String path = audioClip.getCachedPath();
        if (!path.startsWith("/")) path = "/" + path;
        Media media = new Media(Objects.requireNonNull(YieldFX.class.getResource(path)).toExternalForm());
        MediaPlayer player = new MediaPlayer(media);
        player.setOnEndOfMedia(player::stop);
        players.put(audioPlayer.getPlayerID(), player);
        if (audioClip.isFlushAfterLoad())
            audioClip.flush();
    }

    @Override
    public void setMicrosecondPosition(AudioPlayer audioPlayer, long l) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        player.seek(new Duration(l / 1000f));
    }

    @Override
    public long getMicrosecondPosition(AudioPlayer audioPlayer) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        return (long) player.currentTimeProperty().get().toMillis();
    }

    @Override
    public long getMicrosecondLength(AudioPlayer audioPlayer) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        return (long) player.getTotalDuration().toMillis();
    }

    @Override
    public float getVolume(AudioPlayer audioPlayer) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        return (float) player.getVolume();
    }

    @Override
    public void setVolume(AudioPlayer audioPlayer, float v) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        player.setVolume(v);
    }

    @Override
    public void pausePlayer(AudioPlayer audioPlayer) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        player.pause();
    }

    @Override
    public void resumePlayer(AudioPlayer audioPlayer) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        player.play();
    }

    @Override
    public void unloadPlayer(AudioPlayer audioPlayer) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        players.remove(audioPlayer.getPlayerID());
        player.dispose();
    }

    @Override
    public void unloadAllPlayers() {
        for (MediaPlayer player : players.values()) {
            player.dispose();
        }
        players.clear();
    }

    @Override
    public void setLoop(AudioPlayer audioPlayer, boolean b) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        if (b)
            player.setOnEndOfMedia(() -> {
                player.seek(new Duration(0));
                player.play();
            });
        else player.setOnEndOfMedia(player::stop);
    }

    @Override
    public void setLoop(AudioPlayer audioPlayer, int i) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        playersLoop.put(audioPlayer.getPlayerID(), i);
        player.setOnEndOfMedia(() -> {
            int v = playersLoop.get(audioPlayer.getPlayerID());
            if (v > 0) {
                playersLoop.replace(audioPlayer.getPlayerID(), v - 1);
                player.seek(new Duration(0));
                player.play();
            } else {
                playersLoop.remove(audioPlayer.getPlayerID());
                player.stop();
            }
        });
    }

    @Override
    public boolean isPlayerRunning(AudioPlayer audioPlayer) {
        MediaPlayer player = players.get(audioPlayer.getPlayerID());
        return player.getStatus().equals(MediaPlayer.Status.PLAYING);
    }

    @Override
    public void loadAudioPlayer(AudioPlayer audioPlayer) {

    }

    @Override
    public void loadTexture(Texture texture) {
        loadTexture(texture, new Image(texture.getInputStream()));
    }

    public void loadTexture(Texture texture, Image image) {
        texture.setWidth((int) image.getWidth());
        texture.setHeight((int) image.getHeight());
        texture.setSpecificImage(new WritableImage(image.getPixelReader(), texture.getWidth(), texture.getHeight()));
        texture.setVisualUtils(this);

        Texture x = new Texture(""), y = new Texture(""), xy = new Texture("");
        x.setWidth(texture.getWidth());
        y.setWidth(texture.getWidth());
        xy.setWidth(texture.getWidth());
        x.setHeight(texture.getHeight());
        y.setHeight(texture.getHeight());
        xy.setHeight(texture.getHeight());
        x.setVisualUtils(this);
        y.setVisualUtils(this);
        xy.setVisualUtils(this);

        Canvas c = new Canvas(image.getWidth(), image.getHeight());
        GraphicsContext g = c.getGraphicsContext2D();
        g.clearRect(0, 0, c.getWidth(), c.getHeight());
        g.drawImage(image, image.getWidth(), 0, -image.getWidth(), image.getHeight());
        g.save();
        WritableImage xi = new WritableImage(texture.getWidth(), texture.getHeight());
        c.snapshot(new SnapshotParameters(), xi);
        x.setSpecificImage(xi);

        g.clearRect(0, 0, c.getWidth(), c.getHeight());
        g.drawImage(image, 0, image.getHeight(), image.getWidth(), -image.getHeight());
        g.save();
        WritableImage yi = new WritableImage(texture.getWidth(), texture.getHeight());
        c.snapshot(new SnapshotParameters(), yi);
        y.setSpecificImage(yi);

        g.clearRect(0, 0, c.getWidth(), c.getHeight());
        g.drawImage(image, image.getWidth(), image.getHeight(), -image.getWidth(), -image.getHeight());
        g.save();
        WritableImage xyi = new WritableImage(texture.getWidth(), texture.getHeight());
        c.snapshot(new SnapshotParameters(), xyi);
        y.setSpecificImage(xyi);

        if (texture.isFlushAfterLoad())
            texture.flush();
    }

    public static WritableImage copyImage(Image image) {
        int height = (int) image.getHeight();
        int width = (int) image.getWidth();
        PixelReader pixelReader = image.getPixelReader();
        WritableImage writableImage = new WritableImage(width, height);
        PixelWriter pixelWriter = writableImage.getPixelWriter();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                javafx.scene.paint.Color color = pixelReader.getColor(x, y);
                pixelWriter.setColor(x, y, color);
            }
        }
        return writableImage;
    }

    private WritableImage resample(Image input, float xScaleFactor, float yScaleFactor) {
        final int W = (int) input.getWidth();
        final int H = (int) input.getHeight();

        WritableImage output = new WritableImage(
                (int) (W * xScaleFactor),
                (int) (H * yScaleFactor)
        );

        PixelReader reader = input.getPixelReader();
        PixelWriter writer = output.getPixelWriter();

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                final int argb = reader.getArgb(x, y);
                for (int dy = 0; dy < yScaleFactor; dy++) {
                    for (int dx = 0; dx < xScaleFactor; dx++) {
                        writer.setArgb((int) (x * xScaleFactor + dx), (int) (y * yScaleFactor + dy), argb);
                    }
                }
            }
        }

        return output;
    }

    @Override
    public void unloadTexture(Texture texture) {
        texture.setSpecificImage(null);
    }

    @Override
    public void unloadAllTextures() {

    }

    @Override
    public void clearTexture(Texture texture) {
        Image image = new WritableImage(texture.getWidth(), texture.getHeight());
        loadTexture(texture, image);
    }

    @Override
    public void loadFont(String s, String s1, float v, int i) {
        FontWeight weight = FontWeight.NORMAL;
        if (i == 1) {
            weight = FontWeight.BOLD;
        }
        Font font;
        if (i == 2) font = Font.font(s1, FontPosture.ITALIC, v);
        else font = Font.font(s1, weight, v);
        fonts.put(s, font);
    }

    @Override
    public void loadFont(String s, float v, float v1, int i, RelativeFile relativeFile) {
        fonts.put(s, Font.loadFont(relativeFile.getInputStream(), v));
    }

    @Override
    public void unloadFont(String s) {
        fonts.remove(s);
    }

    @Override
    public com.xebisco.yield.Color[][] getTextureColors(Texture texture) {
        return new com.xebisco.yield.Color[0][];
    }

    @Override
    public void setTextureColors(Texture texture, com.xebisco.yield.Color[][] colors) {
        PixelWriter writer = ((WritableImage) texture.getSpecificImage()).getPixelWriter();
        for(int x = 0; x < colors.length; x++)
            for(int y = 0; y < colors[0].length; y++)
                writer.setColor(x, y, new Color(colors[x][y].getR(), colors[x][y].getG(), colors[x][y].getB(), colors[x][y].getA()));
    }

    @Override
    public void setPixel(Texture texture, com.xebisco.yield.Color color, Vector2 vector2) {
        ((WritableImage) texture.getSpecificImage()).getPixelWriter().setColor((int) vector2.x, (int) vector2.y, new Color(color.getR(), color.getG(), color.getB(), color.getA()));
    }

    @Override
    public Texture cutTexture(Texture texture, int i, int i1, int i2, int i3) {
        Texture tex = new Texture("");
        Canvas c = new Canvas(i2, i3);
        GraphicsContext g = c.getGraphicsContext2D();
        g.drawImage((Image) texture.getSpecificImage(), -i, -i1);
        WritableImage image = new WritableImage(i2, i3);
        c.snapshot(new SnapshotParameters(), image);
        loadTexture(tex, image);
        return tex;
    }

    @Override
    public Texture duplicate(Texture texture) {
        Texture tex = new Texture(texture.getCachedPath());
        loadTexture(tex, resample((Image) texture.getSpecificImage(), 1, 1));
        return tex;
    }

    @Override
    public Texture overlayTexture(Texture texture, Texture texture1, Vector2 vector2, Vector2 vector21) {
        Texture tex = new Texture("");
        Canvas c = new Canvas(texture.getWidth(), texture.getHeight());
        GraphicsContext g = c.getGraphicsContext2D();
        g.drawImage((Image) texture.getSpecificImage(), vector2.x, vector2.y);
        g.drawImage((Image) texture1.getSpecificImage(), vector21.x, vector21.y);
        WritableImage image = new WritableImage(texture.getWidth(), texture.getHeight());
        c.snapshot(new SnapshotParameters(), image);
        loadTexture(tex, image);
        return tex;
    }

    @Override
    public Texture scaleTexture(Texture texture, int i, int i1) {
        Texture tex = new Texture(texture.getCachedPath());
        loadTexture(tex, resample((Image) texture.getSpecificImage(), (float) i / (float) texture.getWidth(), (float) i1 / (float) texture.getHeight()));
        return tex;
    }

    public Set<Renderable> getRenderables() {
        return renderables;
    }

    public void setRenderables(Set<Renderable> renderables) {
        this.renderables = renderables;
    }

    public Set<Integer> getPressing() {
        return pressing;
    }

    public void setPressing(Set<Integer> pressing) {
        this.pressing = pressing;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public Map<String, Font> getFonts() {
        return fonts;
    }

    public void setFonts(Map<String, Font> fonts) {
        this.fonts = fonts;
    }

    public Group getRoot() {
        return root;
    }

    public void setRoot(Group root) {
        this.root = root;
    }

    public int getMouseX() {
        return mouseX;
    }

    public void setMouseX(int mouseX) {
        this.mouseX = mouseX;
    }

    public int getMouseY() {
        return mouseY;
    }

    public void setMouseY(int mouseY) {
        this.mouseY = mouseY;
    }

    public YldTask getThreadTask() {
        return threadTask;
    }

    public Scene getScene() {
        return scene;
    }

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    public Map<Integer, MediaPlayer> getPlayers() {
        return players;
    }

    public Map<Integer, Integer> getPlayersLoop() {
        return playersLoop;
    }

    public Affine getAffinetransform() {
        return affinetransform;
    }

    public void setAffinetransform(Affine affinetransform) {
        this.affinetransform = affinetransform;
    }

    public int getInitialWidth() {
        return initialWidth;
    }

    public void setInitialWidth(int initialWidth) {
        this.initialWidth = initialWidth;
    }

    public int getInitialHeight() {
        return initialHeight;
    }

    public void setInitialHeight(int initialHeight) {
        this.initialHeight = initialHeight;
    }

    public Color getBgColor() {
        return bgColor;
    }

    public void setBgColor(Color bgColor) {
        this.bgColor = bgColor;
    }
}
