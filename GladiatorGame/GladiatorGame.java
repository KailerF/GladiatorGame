import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.sound.sampled.*;

/**
 * GLADIATOR ARENA 3D - ENGINE 9.5 (Polish Update)
 * Features:
 * - SAFE Fullscreen Mode (Borderless Window)
 * - Camera Shake on Damage
 * - Movement Logic Normalized (Standard WASD)
 * - Improved Spike Texture Mapping
 * - Player HP 200
 */
public class GladiatorGame extends JFrame implements Runnable, KeyListener, MouseListener, MouseMotionListener {

    // Config
    private static int WIDTH = 800;
    private static int HEIGHT = 600;
    private static boolean isFullscreen = false;
    private static final String TITLE = "Gladiator Arena 3D: Champion Edition";

    // Game States
    private enum State { MENU, SETTINGS, PLAYING, GAME_OVER, VICTORY }
    private State gameState = State.MENU;

    // Engine
    private Thread thread;
    private boolean running;
    private BufferedImage image;
    private int[] pixels;
    private Camera camera;
    private Screen screen;
    private InputHandler input;
    
    // Game Objects
    private Level level;
    private Player player;
    private SoundEngine soundEngine;
    private CombatSystem combatSystem;
    private ParticleSystem particleSystem;
    
    private int damageFlashTimer = 0;
    private double damageShake = 0; // Camera shake intensity

    public static void main(String[] args) {
        GladiatorGame game = new GladiatorGame();
        game.start();
    }

    public GladiatorGame() {
        initScreenBuffers();
        input = new InputHandler();
        
        setSize(WIDTH, HEIGHT);
        setResizable(false);
        setTitle(TITLE);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setBackground(Color.black);
        setLocationRelativeTo(null);
        
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        
        setVisible(true);
        
        soundEngine = new SoundEngine();
        TextureManager.init(); 
    }

    private void initScreenBuffers() {
        image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        screen = new Screen(WIDTH, HEIGHT);
    }
    
    // SAFER Fullscreen Toggle (Borderless Window)
    private void toggleFullscreen() {
        dispose(); 
        isFullscreen = !isFullscreen;
        if(isFullscreen) {
            setExtendedState(JFrame.MAXIMIZED_BOTH);
            setUndecorated(true);
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            WIDTH = screenSize.width;
            HEIGHT = screenSize.height;
        } else {
            setExtendedState(JFrame.NORMAL);
            setUndecorated(false);
            WIDTH = 800;
            HEIGHT = 600;
            setSize(WIDTH, HEIGHT);
            setLocationRelativeTo(null);
        }
        initScreenBuffers();
        setVisible(true);
    }

    private void initGame() {
        level = new Level(128, 128); 
        camera = new Camera(64.5, 64.5, 1, 0, 0.66);
        player = new Player(camera);
        
        combatSystem = new CombatSystem(player, level, soundEngine);
        particleSystem = new ParticleSystem();
        damageFlashTimer = 0;
        damageShake = 0;
        
        input.cursorLocked = true;
        hideCursor(true);
    }

    public synchronized void start() {
        running = true;
        thread = new Thread(this, "GameEngine");
        thread.start();
    }

    public void run() {
        long lastTime = System.nanoTime();
        final double ns = 1000000000.0 / 60.0;
        double delta = 0;
        
        while (running) {
            long now = System.nanoTime();
            delta += (now - lastTime) / ns;
            lastTime = now;
            while (delta >= 1) {
                update();
                delta--;
            }
            render();
        }
    }

    private void update() {
        if (gameState == State.PLAYING) {
            updateGame();
        } 
    }

    private void updateGame() {
        double moveSpeed = 0.08; // Slightly faster movement
        
        if (player.stamina < player.maxStamina) player.stamina += 0.2;
        
        if (input.block && player.stamina > 0) {
            player.isBlocking = true;
            moveSpeed *= 0.5; 
            player.stamina -= 0.3;
        } else {
            player.isBlocking = false;
        }

        // Mouse Look
        if (input.cursorLocked) {
             int cx = getWidth()/2;
             int cy = getHeight()/2;
             if (input.mouseX != cx) {
                double rot = (input.mouseX - cx) * 0.0015;
                camera.rotate(rot);
                try {
                    Robot robot = new Robot();
                    Point p = getLocationOnScreen();
                    robot.mouseMove(p.x + cx, p.y + cy);
                    input.mouseX = cx; 
                } catch (Exception e) {}
            }
        }
        
        // STANDARD MOVEMENT (Fixed: No Inversion)
        double dx = 0, dy = 0;
        if (input.forward) { dx += camera.xDir; dy += camera.yDir; }
        if (input.back) { dx -= camera.xDir; dy -= camera.yDir; }
        
        // Strafe Left: -Y, X
        if (input.strafeLeft) { 
            dx -= camera.yDir * 0.8; 
            dy += camera.xDir * 0.8; 
        } 
        // Strafe Right: Y, -X
        if (input.strafeRight) { 
            dx += camera.yDir * 0.8; 
            dy -= camera.xDir * 0.8; 
        }
        
        if (dx != 0 || dy != 0) {
            player.headBob += 0.15;
            double destX = camera.xPos + dx * moveSpeed;
            double destY = camera.yPos + dy * moveSpeed;
            
            if(!level.isWall(destX + 0.3, camera.yPos)) camera.xPos = destX;
            if(!level.isWall(camera.xPos, destY + 0.3)) camera.yPos = destY;
            
            // TRAP CHECK
            int mapX = (int)camera.xPos;
            int mapY = (int)camera.yPos;
            if (level.map[mapX][mapY] == 2) { 
                if (player.trapTimer == 0) {
                    player.health -= 15;
                    damageFlashTimer = 10;
                    damageShake = 15.0; // Violent shake
                    soundEngine.playTrap();
                    player.trapTimer = 40; 
                }
            }
        } else {
            player.headBob = 0;
        }
        
        if (player.trapTimer > 0) player.trapTimer--;
        
        // Camera Shake Decay
        if (damageShake > 0) damageShake *= 0.9;

        // Combat
        if (input.attack && player.attackTimer == 0 && player.stamina > 15 && !player.isBlocking) {
            player.attackTimer = 20;
            player.stamina -= 15;
            double r = Math.random();
            if (r < 0.33) player.currentAttack = Player.AttackType.SLASH;
            else if (r < 0.66) player.currentAttack = Player.AttackType.STAB;
            else player.currentAttack = Player.AttackType.OVERHEAD;
            
            soundEngine.playWoosh();
            combatSystem.playerAttack();
        }
        
        if (player.attackTimer > 0) player.attackTimer--;
        if (damageFlashTimer > 0) damageFlashTimer--;

        boolean enemiesAlive = false;
        for (Iterator<Enemy> it = level.enemies.iterator(); it.hasNext();) {
            Enemy e = it.next();
            if(!e.isDead) enemiesAlive = true;
            
            boolean hitPlayer = combatSystem.updateEnemyAI(e);
            if(hitPlayer) {
                damageFlashTimer = 10;
                damageShake = 10.0; // Hit shake
            }
            
            if (e.isDead) {
                if (e.deathTimer > 0) e.deathTimer--;
                else it.remove();
            }
        }
        
        particleSystem.update();
        
        if (player.health <= 0) {
            gameState = State.GAME_OVER;
            hideCursor(false);
            soundEngine.playDeath();
        }
        
        if (!enemiesAlive && level.enemies.isEmpty() && gameState != State.VICTORY) {
            level.currentWave++;
            if(level.currentWave > 5) {
                gameState = State.VICTORY;
                hideCursor(false);
            } else {
                level.spawnWave(level.currentWave + 2);
                soundEngine.playHorn();
            }
        }
    }

    private void render() {
        BufferStrategy bs = getBufferStrategy();
        if (bs == null) { createBufferStrategy(3); return; }
        Graphics g = bs.getDrawGraphics();

        if (gameState == State.PLAYING) {
            double verticalBob = Math.sin(player.headBob) * 10.0;
            
            // Apply Camera Shake to Render Offset
            double shakeOffset = (Math.random() - 0.5) * damageShake;
            
            screen.render(camera, level, pixels, verticalBob + shakeOffset);
            
            List<Sprite> renderList = new ArrayList<>(level.enemies);
            renderList.add(level.emperor);
            renderList.addAll(particleSystem.particles);
            screen.renderSprites(camera, renderList, pixels, verticalBob + shakeOffset);

            g.drawImage(image, 0, 0, WIDTH, HEIGHT, null);

            if(damageFlashTimer > 0) {
                g.setColor(new Color(255, 0, 0, 100));
                g.fillRect(0, 0, WIDTH, HEIGHT);
            }

            renderHUD(g, verticalBob);
            renderMinimap(g);
        } else {
            renderMenu(g);
        }

        g.dispose();
        bs.show();
    }

    private void renderMenu(Graphics g) {
        g.setColor(new Color(20, 10, 10));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        
        g.setColor(new Color(212, 175, 55)); 
        g.setFont(new Font("Serif", Font.BOLD, 50));
        
        String title = "GLADIATOR ARENA";
        int tw = g.getFontMetrics().stringWidth(title);
        g.drawString(title, WIDTH/2 - tw/2, 100);
        
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        
        int midX = WIDTH/2;
        
        if (gameState == State.MENU) {
            drawButton(g, "PLAY", midX - 100, 250, 200, 50);
            drawButton(g, "SETTINGS", midX - 100, 320, 200, 50);
            drawButton(g, "QUIT", midX - 100, 390, 200, 50);
        } else if (gameState == State.SETTINGS) {
            drawButton(g, "RES: " + WIDTH + "x" + HEIGHT, midX - 150, 200, 300, 50);
            drawButton(g, "FULLSCREEN: " + (isFullscreen ? "ON" : "OFF"), midX - 150, 270, 300, 50);
            drawButton(g, "BACK", midX - 100, 400, 200, 50);
        } else if (gameState == State.GAME_OVER) {
            g.setColor(Color.RED);
            String msg = "YOU DIED";
            g.drawString(msg, midX - g.getFontMetrics().stringWidth(msg)/2, 200);
            drawButton(g, "MAIN MENU", midX - 120, 300, 240, 50);
        } else if (gameState == State.VICTORY) {
            g.setColor(Color.GREEN);
            String msg = "VICTORY!";
            g.drawString(msg, midX - g.getFontMetrics().stringWidth(msg)/2, 200);
            drawButton(g, "MAIN MENU", midX - 120, 300, 240, 50);
        }
    }
    
    private void drawButton(Graphics g, String text, int x, int y, int w, int h) {
        g.setColor(new Color(50, 30, 20));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(212, 175, 55));
        g.drawRect(x, y, w, h);
        
        int strW = g.getFontMetrics().stringWidth(text);
        int strH = g.getFontMetrics().getAscent();
        g.drawString(text, x + (w - strW)/2, y + (h + strH)/2 - 5);
    }

    private void renderMinimap(Graphics g) {
        int size = 130;
        int x = 20, y = 20;
        
        Graphics2D g2d = (Graphics2D)g;
        Shape oldClip = g2d.getClip();
        g2d.setClip(new Ellipse2D.Float(x, y, size, size));
        
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRect(x, y, size, size);
        
        int cx = x + size/2;
        int cy = y + size/2;
        double scale = 1.5; 
        
        g.setColor(Color.DARK_GRAY);
        for(int i=-20; i<20; i++) {
            for(int j=-20; j<20; j++) {
                int mx = (int)camera.xPos + i;
                int my = (int)camera.yPos + j;
                if(mx>=0 && mx<level.w && my>=0 && my<level.h) {
                    if(level.map[mx][my] == 2) { 
                        int dx = (int)(i * scale * 4); 
                        int dy = (int)(j * scale * 4);
                        g.fillRect(cx + dx - 2, cy + dy - 2, 4, 4);
                    }
                }
            }
        }

        g.setColor(Color.RED);
        for(Enemy e : level.enemies) {
            if(e.isDead) continue;
            double relX = (e.x - camera.xPos);
            double relY = (e.y - camera.yPos);
            int dx = (int)(relX * scale * 4);
            int dy = (int)(relY * scale * 4);
            if(Math.abs(dx) < size/2 && Math.abs(dy) < size/2) {
                g.fillOval(cx + dx - 3, cy + dy - 3, 6, 6);
            }
        }
        
        g.setColor(Color.GREEN);
        g.fillOval(cx-3, cy-3, 6, 6);
        g.drawLine(cx, cy, cx + (int)(camera.xDir*15), cy + (int)(camera.yDir*15));
        
        g2d.setClip(oldClip);
        g2d.setColor(new Color(212, 175, 55));
        g2d.setStroke(new BasicStroke(3));
        g2d.drawOval(x, y, size, size);
        g2d.setStroke(new BasicStroke(1));
    }

    private void renderHUD(Graphics g, double bobOffset) {
        int handY = (int)(bobOffset);
        
        if (player.isBlocking) {
            int sx = 200, sy = HEIGHT - 280 + handY;
            g.setColor(new Color(60, 40, 20));
            g.fillOval(sx, sy, 220, 220);
            g.setColor(Color.LIGHT_GRAY);
            ((Graphics2D)g).setStroke(new BasicStroke(5));
            g.drawOval(sx, sy, 220, 220);
            ((Graphics2D)g).setStroke(new BasicStroke(1));
            g.fillOval(sx+85, sy+85, 50, 50);
        } else {
            g.setColor(new Color(80, 40, 10));
            g.fillOval(-80, HEIGHT - 120 + handY, 180, 180);
        }

        Graphics2D g2 = (Graphics2D) g;
        int swordX = WIDTH - 200;
        int swordY = HEIGHT - 100 + handY;
        
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (player.attackTimer > 0) {
            double p = 1.0 - (player.attackTimer / 20.0);
            if (player.currentAttack == Player.AttackType.SLASH) {
                swordX -= (int)(Math.cos(p*Math.PI)*200) + 100;
                g2.rotate(-0.5 + p*2.0, swordX+50, swordY+50);
            } else if (player.currentAttack == Player.AttackType.STAB) {
                swordY -= (int)(Math.sin(p*Math.PI)*150);
                swordX -= 50;
            } else {
                g2.rotate(-Math.PI/2 + Math.sin(p*Math.PI)*2.5, swordX+50, swordY+50);
                swordX -= (int)(Math.sin(p*Math.PI)*50);
                swordY -= (int)(Math.sin(p*Math.PI)*50);
            }
        } else {
            g2.rotate(-0.2, swordX, swordY);
        }
        
        int[] bx = {swordX, swordX+25, swordX+25, swordX};
        int[] by = {swordY, swordY, swordY-250, swordY-280};
        g2.setPaint(new GradientPaint(swordX, swordY, Color.GRAY, swordX+20, swordY-300, Color.WHITE));
        g2.fillPolygon(bx, by, 4);
        g2.setColor(new Color(101, 67, 33));
        g2.fillRect(swordX-5, swordY, 35, 15);
        g2.fillRect(swordX+5, swordY+15, 15, 60);
        g2.setTransform(new java.awt.geom.AffineTransform());

        g.setColor(new Color(255, 255, 255, 128));
        g.fillOval(WIDTH/2-3, HEIGHT/2-3, 6, 6);

        g.setColor(Color.BLACK);
        g.fillRect(160, 20, 304, 24);
        g.setColor(new Color(180, 0, 0));
        g.fillRect(162, 22, (int)((player.health/200.0)*300), 20);
        g.setColor(Color.WHITE);
        g.drawString("HEALTH: " + (int)player.health, 170, 37);

        g.setColor(Color.BLACK);
        g.fillRect(160, 50, 204, 14);
        g.setColor(Color.YELLOW);
        g.fillRect(162, 52, (int)(player.stamina * 2), 10);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Serif", Font.BOLD, 30));
        g.drawString("WAVE " + level.currentWave, WIDTH/2 - 50, 50);
    }

    private void hideCursor(boolean hide) {
        if(hide) {
            BufferedImage cursorImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Cursor blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(cursorImg, new Point(0, 0), "blank cursor");
            getContentPane().setCursor(blankCursor);
        } else {
            getContentPane().setCursor(Cursor.getDefaultCursor());
        }
    }

    // --- INPUT ---
    public void mousePressed(MouseEvent e) {
        int mx = e.getX(); int my = e.getY();
        int midX = WIDTH/2;

        if (gameState == State.MENU) {
            if(mx > midX - 100 && mx < midX + 100) {
                if(my > 250 && my < 300) { 
                    initGame();
                    gameState = State.PLAYING;
                }
                if(my > 320 && my < 370) gameState = State.SETTINGS;
                if(my > 390 && my < 440) System.exit(0);
            }
        } else if (gameState == State.SETTINGS) {
            if(mx > midX - 150 && mx < midX + 150) {
                if(my > 200 && my < 250) { // RESOLUTION
                    if(WIDTH == 800) { WIDTH = 1024; HEIGHT = 768; }
                    else if(WIDTH == 1024) { WIDTH = 1280; HEIGHT = 720; }
                    else if(WIDTH == 1280) { WIDTH = 1920; HEIGHT = 1080; }
                    else { WIDTH = 800; HEIGHT = 600; }
                    if(!isFullscreen) {
                        setSize(WIDTH, HEIGHT);
                        setLocationRelativeTo(null);
                    }
                    initScreenBuffers();
                }
                if(my > 270 && my < 320) { // FULLSCREEN
                    toggleFullscreen();
                }
            }
            if(mx > midX - 100 && mx < midX + 100 && my > 400 && my < 450) {
                gameState = State.MENU; 
            }
        } else if (gameState == State.GAME_OVER || gameState == State.VICTORY) {
            if(mx > midX - 120 && mx < midX + 120 && my > 300 && my < 350) {
                gameState = State.MENU;
                hideCursor(false);
            }
        } else if (gameState == State.PLAYING) {
            if(SwingUtilities.isLeftMouseButton(e)) input.attack = true;
            if(SwingUtilities.isRightMouseButton(e)) input.block = true;
        }
    }
    public void mouseReleased(MouseEvent e) {
        if (gameState == State.PLAYING) {
            if(SwingUtilities.isLeftMouseButton(e)) input.attack = false;
            if(SwingUtilities.isRightMouseButton(e)) input.block = false;
        }
    }
    public void mouseMoved(MouseEvent e) { input.mouseX = e.getX(); }
    public void mouseDragged(MouseEvent e) { input.mouseX = e.getX(); }
    public void keyPressed(KeyEvent k) {
        int code = k.getKeyCode();
        if(code == KeyEvent.VK_W) input.forward = true;
        if(code == KeyEvent.VK_S) input.back = true;
        if(code == KeyEvent.VK_A) input.strafeLeft = true;
        if(code == KeyEvent.VK_D) input.strafeRight = true;
        if(code == KeyEvent.VK_SPACE) input.attack = true;
        if(code == KeyEvent.VK_SHIFT) input.block = true;
        if(code == KeyEvent.VK_ESCAPE) {
            if(gameState == State.PLAYING) {
                gameState = State.MENU;
                input.cursorLocked = false;
                hideCursor(false);
            }
        }
    }
    public void keyReleased(KeyEvent k) {
        int code = k.getKeyCode();
        if(code == KeyEvent.VK_W) input.forward = false;
        if(code == KeyEvent.VK_S) input.back = false;
        if(code == KeyEvent.VK_A) input.strafeLeft = false;
        if(code == KeyEvent.VK_D) input.strafeRight = false;
        if(code == KeyEvent.VK_SPACE) input.attack = false;
        if(code == KeyEvent.VK_SHIFT) input.block = false;
    }
    public void keyTyped(KeyEvent e) {}
    public void mouseClicked(MouseEvent e) {}
    public void mouseEntered(MouseEvent e) {}
    public void mouseExited(MouseEvent e) {}

    private class InputHandler {
        public boolean forward, back, strafeLeft, strafeRight, attack, block;
        public int mouseX = WIDTH / 2;
        public boolean cursorLocked = false;
    }

    // --- LOGIC CLASSES ---

    private class CombatSystem {
        Player player;
        Level level;
        SoundEngine sound;
        public CombatSystem(Player p, Level l, SoundEngine s) { this.player = p; this.level = l; this.sound = s; }
        public void playerAttack() {
            boolean hit = false;
            for(Enemy e : level.enemies) {
                if(e.isDead) continue;
                double dx = e.x - player.camera.xPos;
                double dy = e.y - player.camera.yPos;
                double dist = Math.sqrt(dx*dx + dy*dy);
                double dot = (dx/dist)*player.camera.xDir + (dy/dist)*player.camera.yDir;
                
                if(dist < 3.0 && dot > 0.5) {
                    hit = true;
                    double dmg = 35;
                    if(player.currentAttack == Player.AttackType.OVERHEAD) dmg=50;
                    
                    double edx = Math.cos(e.angle); double edy = Math.sin(e.angle);
                    if((dx/dist)*edx + (dy/dist)*edy > 0.5) { sound.playCrit(); dmg*=2; }
                    else sound.playHit();
                    
                    e.takeDamage(dmg);
                    e.state = Enemy.State.STUNNED; e.stunTimer = 25; sound.playStun();
                    e.x += (dx/dist)*1.2; e.y += (dy/dist)*1.2;
                    for(int i=0;i<10;i++) particleSystem.spawnBlood(e.x,e.y,0);
                }
            }
            if(!hit) sound.playWoosh();
        }
        public boolean updateEnemyAI(Enemy e) {
            if(e.isDead) return false;
            double dx = player.camera.xPos - e.x;
            double dy = player.camera.yPos - e.y;
            double dist = Math.sqrt(dx*dx + dy*dy);
            e.angle = Math.atan2(dy, dx);
            
            switch(e.state) {
                case IDLE: if(dist < 20) e.state = Enemy.State.CHASE; break;
                case CHASE:
                    if(dist > 2.0) {
                        e.x += Math.cos(e.angle)*e.speed;
                        e.y += Math.sin(e.angle)*e.speed;
                    } else {
                        e.state = Enemy.State.WINDUP; e.attackTimer = 30;
                    }
                    break;
                case WINDUP:
                    if(--e.attackTimer <= 0) { e.state = Enemy.State.ATTACK; e.attackTimer = 10; sound.playGrunt(); }
                    break;
                case ATTACK:
                    if(e.attackTimer == 5 && dist < 2.5) {
                        if(player.isBlocking) { sound.playClang(); player.stamina-=10; e.state = Enemy.State.STUNNED; e.stunTimer=40; return false; }
                        else { player.health -= 10; sound.playHurt(); return true; }
                    }
                    if(--e.attackTimer <= 0) { e.state = Enemy.State.COOLDOWN; e.stunTimer = 40; }
                    break;
                case COOLDOWN:
                    if(--e.stunTimer <= 0) e.state = Enemy.State.CHASE;
                    break;
                case STUNNED:
                    if(--e.stunTimer <= 0) e.state = Enemy.State.CHASE;
                    break;
            }
            return false;
        }
    }

    private static class Player {
        enum AttackType { SLASH, STAB, OVERHEAD }
        AttackType currentAttack = AttackType.SLASH;
        double health = 200; 
        double stamina = 100, maxStamina = 100;
        boolean isBlocking = false;
        int attackTimer = 0, trapTimer = 0;
        double headBob = 0;
        Camera camera;
        public Player(Camera c) { this.camera = c; }
    }

    private static class Sprite { double x, y; int textureId; double angle; }
    private static class Enemy extends Sprite {
        enum State { IDLE, CHASE, WINDUP, ATTACK, COOLDOWN, STUNNED, DEATH }
        State state = State.IDLE;
        double hp = 100, speed = 0.04;
        int attackTimer, stunTimer, deathTimer;
        boolean isDead = false;
        public Enemy(double x, double y) { this.x=x; this.y=y; this.textureId=4; }
        public void takeDamage(double d) { hp-=d; if(hp<=0 && !isDead) { isDead=true; textureId=6; deathTimer=100; }}
    }

    private static class Level {
        int w, h;
        int[][] map; 
        List<Enemy> enemies = new ArrayList<>();
        Sprite emperor;
        int currentWave = 1;
        
        public Level(int w, int h) {
            this.w = w; this.h = h;
            map = new int[w][h];
            generateMap();
            emperor = new Sprite(); emperor.x = w/2.0; emperor.y = 1.5; emperor.textureId = 3;
            spawnWave(3);
        }
        
        public boolean isWall(double x, double y) {
            if(x<0 || x>=w || y<0 || y>=h) return true;
            return map[(int)x][(int)y] == 1;
        }

        private void generateMap() {
            int cx = w/2, cy = h/2;
            for(int x=0; x<w; x++) {
                for(int y=0; y<h; y++) {
                    double dx = x-cx; double dy = y-cy;
                    double val = (dx*dx)/(40.0*40.0) + (dy*dy)/(25.0*25.0);
                    
                    if(val >= 1.0) map[x][y] = 1; // Wall
                    else {
                        // Random Spikes inside
                        if(Math.random() < 0.02 && val < 0.8) map[x][y] = 2; // Spike Trap
                        else map[x][y] = 0; // Floor
                    }
                }
            }
        }
        
        public void spawnWave(int count) {
            for(int i=0; i<count; i++) {
                double a = Math.random() * Math.PI * 2;
                double d = 10 + Math.random() * 15;
                double ex = w/2.0 + Math.cos(a)*d;
                double ey = h/2.0 + Math.sin(a)*d*0.6;
                if(!isWall(ex, ey) && map[(int)ex][(int)ey] != 2) enemies.add(new Enemy(ex, ey));
            }
        }
    }

    private static class Camera {
        double xPos, yPos, xDir, yDir, xPlane, yPlane;
        public Camera(double x, double y, double xd, double yd, double xp) {
            xPos=x; yPos=y; xDir=xd; yDir=yd; xPlane=0; yPlane=xp;
        }
        public void rotate(double a) {
            double oldX = xDir;
            xDir = xDir * Math.cos(a) - yDir * Math.sin(a);
            yDir = oldX * Math.sin(a) + yDir * Math.cos(a);
            double oldP = xPlane;
            xPlane = xPlane * Math.cos(a) - yPlane * Math.sin(a);
            yPlane = oldP * Math.sin(a) + yPlane * Math.cos(a);
        }
    }

    // --- RENDERING ---
    private static class TextureManager {
        static List<Texture> textures = new ArrayList<>();
        public static void init() {
            textures.add(Texture.genWall()); // 0: Crowd
            textures.add(Texture.genGate()); // 1: Gate
            textures.add(Texture.genFloor(0xD2B48C)); // 2: Sand
            textures.add(Texture.genSprite(0x800080)); // 3: Emperor
            textures.add(Texture.genGlad(false, false)); // 4: Idle
            textures.add(Texture.genGlad(true, false)); // 5: Atk
            textures.add(Texture.genBlood()); // 6: Dead
            textures.add(Texture.genGlad(false, true)); // 7: Stun
            textures.add(Texture.genFloor(0x333333)); // 8: SPIKE (Dark Grey, Better Texture)
        }
    }
    
    private static class Texture {
        int[] pixels; int size=64;
        public Texture() { pixels = new int[64*64]; }
        
        static Texture genWall() {
            Texture t = new Texture();
            for(int i=0; i<4096; i++) {
                int y = i/64;
                int col = 0xCEC8B4;
                if(y%6==0) col = 0x9A8B7C;
                if(y%6>2 && (i%64 + (y%6)*13)%4!=0) {
                    double r=Math.random();
                    if(r>0.75) col=0xFFCCCC; else if(r>0.5) col=0xEEEEFF;
                    else if(r>0.25) col=0xEEDDCC; else col=0xAA9988;
                }
                t.pixels[i] = col;
            }
            return t;
        }
        static Texture genGate() {
            Texture t = new Texture();
            for(int i=0;i<4096;i++) t.pixels[i] = ((i%64)%8==0 || (i/64)%16==0) ? 0x222222 : 0x111111;
            return t;
        }
        static Texture genFloor(int c) {
            Texture t = new Texture();
            for(int i=0;i<4096;i++) {
                int n = (int)(Math.random()*20);
                int r=(c>>16)&0xFF; int g=(c>>8)&0xFF; int b=c&0xFF;
                t.pixels[i] = ((r-n)<<16)|((g-n)<<8)|(b-n);
            }
            return t;
        }
        static Texture genSprite(int c) {
            Texture t = new Texture();
            for(int i=0;i<4096;i++) t.pixels[i] = -1;
            for(int y=10;y<54;y++) for(int x=20;x<44;x++) t.pixels[x+y*64]=c;
            return t;
        }
        static Texture genGlad(boolean atk, boolean stun) {
            Texture t = new Texture();
            int cx=32;
            for(int y=0;y<64;y++) for(int x=0;x<64;x++) {
                t.pixels[x+y*64] = -1;
                int skin=stun?0xFFFFEE:0xD4AF37; int armor=stun?0xFFFFFF:0xC0C0C0;
                if(y>45 && (Math.abs(x-cx-6)<4||Math.abs(x-cx+6)<4)) t.pixels[x+y*64]=0x8B4513;
                else if(y>25 && Math.abs(x-cx)<11) t.pixels[x+y*64]=armor;
                else if(y>8 && y<=25 && (x-cx)*(x-cx)+(y-18)*(y-18)<64) t.pixels[x+y*64]=skin;
                if((x-18)*(x-18)+(y-35)*(y-35)<100) t.pixels[x+y*64]=(x-18)*(x-18)+(y-35)*(y-35)<16?0xFFFFFF:0x8B0000;
                boolean sw = (atk && x>42 && x<62 && y>15 && y<22) || (!atk && x>46 && x<51 && y>30 && y<55);
                if(sw) t.pixels[x+y*64]=0xEEEEEE;
            }
            return t;
        }
        static Texture genBlood() {
            Texture t = new Texture();
            for(int i=0;i<4096;i++) if(Math.random()<0.3 && (i%64-32)*(i%64-32)+(i/64-32)*(i/64-32)<100) t.pixels[i]=0xAA0000; else t.pixels[i]=-1;
            return t;
        }
    }

    private static class Screen {
        int w, h; double[] zBuffer;
        public Screen(int w, int h) { this.w=w; this.h=h; zBuffer=new double[w]; }
        public void render(Camera cam, Level lvl, int[] pix, double bob) {
            // Sky & Floor
            for(int i=0; i<pix.length/2; i++) pix[i] = 0x87CEEB;
            for(int i=pix.length/2; i<pix.length; i++) pix[i] = 0xD2B48C;

            for(int x=0; x<w; x++) {
                double cx = 2*x/(double)w - 1;
                double rdx = cam.xDir + cam.xPlane*cx;
                double rdy = cam.yDir + cam.yPlane*cx;
                int mx=(int)cam.xPos, my=(int)cam.yPos;
                double ddx = Math.abs(1/rdx), ddy = Math.abs(1/rdy);
                double sdx, sdy, pwd;
                int sx, sy, side=0;
                
                if(rdx<0) { sx=-1; sdx=(cam.xPos-mx)*ddx; } else { sx=1; sdx=(mx+1.0-cam.xPos)*ddx; }
                if(rdy<0) { sy=-1; sdy=(cam.yPos-my)*ddy; } else { sy=1; sdy=(my+1.0-cam.yPos)*ddy; }
                
                while(true) {
                    if(sdx<sdy) { sdx+=ddx; mx+=sx; side=0; } else { sdy+=ddy; my+=sy; side=1; }
                    if(lvl.isWall(mx, my)) break;
                }
                
                pwd = (side==0) ? (mx-cam.xPos+(1-sx)/2)/rdx : (my-cam.yPos+(1-sy)/2)/rdy;
                zBuffer[x] = pwd;
                
                int lh = (int)(h/pwd);
                int start = -lh/2 + h/2 + (int)bob;
                int end = lh/2 + h/2 + (int)bob;
                if(start<0) start=0; if(end>=h) end=h-1;
                
                Texture t = TextureManager.textures.get(0);
                double wx = (side==0) ? cam.yPos+pwd*rdy : cam.xPos+pwd*rdx;
                wx -= Math.floor(wx);
                int tx = (int)(wx*64);
                if((side==0 && rdx>0) || (side==1 && rdy<0)) tx = 63-tx;
                
                for(int y=start; y<end; y++) {
                    int d = y*256 - h*128 + lh*128 - (int)bob*256;
                    int ty = ((d*64)/lh)/256;
                    int c = t.pixels[64*ty+tx];
                    if(side==1) c = (c>>1)&8355711;
                    pix[x+y*w] = c;
                }
                
                if(end<h) {
                    double distWall = pwd;
                    double distPlayer = 0.0;
                    if(end<0) end=h;
                    for(int y=end+1; y<h; y++) {
                        double currentDist = h / (2.0*y - h - 2.0*bob);
                        double weight = (currentDist - distPlayer)/(distWall - distPlayer);
                        double cfx = weight*((side==0)?(mx+wx):(mx+(1.0-sx)/2.0)) + (1.0-weight)*cam.xPos;
                        double cfy = weight*((side==0)?(my+(1.0-sy)/2.0):(my+wx)) + (1.0-weight)*cam.yPos;
                        
                        int ftx = (int)(cfx*64)%64; int fty = (int)(cfy*64)%64;
                        if(ftx<0) ftx+=64; if(fty<0) fty+=64;
                        
                        int col;
                        if(lvl.map[(int)cfx][(int)cfy] == 2) {
                            // IMPROVED SPIKE VISUALS
                            col = TextureManager.textures.get(8).pixels[64*fty+ftx];
                            // Cross-hatch metallic pattern
                            if((ftx+fty)%8==0 || (ftx-fty)%8==0) col = 0x111111; 
                        } else {
                            col = TextureManager.textures.get(2).pixels[64*fty+ftx];
                            if(Math.abs(cfx%5.0)<0.1 || Math.abs(cfy%5.0)<0.1) col = (col>>1)&8355711;
                        }
                        pix[x+y*w] = col;
                    }
                }
            }
        }
        public void renderSprites(Camera c, List<Sprite> sprites, int[] pix, double bob) {
            sprites.sort((s1, s2) -> Double.compare(
                (c.xPos-s2.x)*(c.xPos-s2.x)+(c.yPos-s2.y)*(c.yPos-s2.y),
                (c.xPos-s1.x)*(c.xPos-s1.x)+(c.yPos-s1.y)*(c.yPos-s1.y)
            ));
            for(Sprite s : sprites) {
                double sx = s.x - c.xPos; double sy = s.y - c.yPos;
                double inv = 1.0/(c.xPlane*c.yDir - c.xDir*c.yPlane);
                double tx = inv * (c.yDir*sx - c.xDir*sy);
                double ty = inv * (-c.yPlane*sx + c.xPlane*sy);
                if(ty <= 0) continue;
                
                int scx = (int)((w/2)*(1 + tx/ty));
                int sh = Math.abs((int)(h/ty));
                int startY = -sh/2 + h/2 + (int)bob; if(startY<0) startY=0;
                int endY = sh/2 + h/2 + (int)bob; if(endY>=h) endY=h-1;
                int sw = Math.abs((int)(h/ty));
                int startX = -sw/2 + scx; if(startX<0) startX=0;
                int endX = sw/2 + scx; if(endX>=w) endX=w-1;
                
                Texture t;
                if(s instanceof Enemy) {
                    Enemy e=(Enemy)s; 
                    if(e.isDead) t=TextureManager.textures.get(6);
                    else if(e.state==Enemy.State.STUNNED) t=TextureManager.textures.get(7);
                    else if(e.state==Enemy.State.ATTACK) t=TextureManager.textures.get(5);
                    else t=TextureManager.textures.get(4);
                } else if(s instanceof Particle) t=TextureManager.textures.get(6);
                else t=TextureManager.textures.get(s.textureId);
                
                for(int stripe=startX; stripe<endX; stripe++) {
                    int texX = (int)(256*(stripe-(-sw/2+scx))*64/sw)/256;
                    if(ty>0 && stripe>0 && stripe<w && ty<zBuffer[stripe]) {
                        for(int y=startY; y<endY; y++) {
                            int d = y*256 - h*128 + sh*128 - (int)bob*256;
                            int texY = ((d*64)/sh)/256;
                            int col = t.pixels[64*texY+texX];
                            if(col!=-1) pix[stripe+y*w] = col;
                        }
                    }
                }
            }
        }
    }

    private static class Particle extends Sprite { double vx, vy; int life; }
    private class ParticleSystem {
        List<Particle> particles = new ArrayList<>();
        void spawnBlood(double x, double y, double z) { 
            Particle p = new Particle(); p.x=x; p.y=y; p.vx=(Math.random()-0.5)*0.1; p.vy=(Math.random()-0.5)*0.1; p.life=20; 
            particles.add(p);
        }
        void update() {
            Iterator<Particle> it = particles.iterator();
            while(it.hasNext()) { Particle p = it.next(); p.x+=p.vx; p.y+=p.vy; if(--p.life<=0) it.remove(); }
        }
    }

    private static class SoundEngine {
        void playWoosh() { play(150, 100, "NOISE"); }
        void playHit() { play(60, 100, "THUD"); }
        void playTrap() { play(800, 200, "CLANG"); }
        void playStun() { play(300, 300, "WOBBLE"); }
        void playClang() { play(600, 400, "METAL"); }
        void playGrunt() { play(100, 200, "LOW"); }
        void playHurt() { play(200, 300, "SAW"); }
        void playCrit() { play(800, 500, "RING"); }
        void playDeath() { play(100, 800, "LOW"); }
        void playHorn() { play(300, 1500, "SAW"); }
        
        void play(int f, int d, String t) {
            new Thread(() -> {
                try {
                    AudioFormat af = new AudioFormat(44100f, 8, 1, true, false);
                    SourceDataLine sdl = AudioSystem.getSourceDataLine(af);
                    sdl.open(af); sdl.start();
                    byte[] b = new byte[d*44];
                    for(int i=0;i<b.length;i++) {
                        double v=0, dt=i/44100.0;
                        if(t.equals("NOISE")) v=(Math.random()-0.5);
                        else if(t.equals("THUD")) v=(Math.random()-0.5)*Math.exp(-dt*20);
                        else if(t.equals("CLANG")) v=Math.sin(dt*f*2*Math.PI)*Math.exp(-dt*10);
                        else if(t.equals("WOBBLE")) v=Math.sin(dt*f*2*Math.PI + Math.sin(dt*20)*10);
                        else if(t.equals("METAL")) v=Math.sin(dt*f*2*Math.PI + Math.sin(dt*f*2.5)*5);
                        else if(t.equals("RING")) v=Math.sin(dt*f*2*Math.PI)*Math.exp(-dt*2);
                        else if(t.equals("LOW")) v=Math.sin(dt*f*2*Math.PI);
                        else v=((dt*f)%1.0)-0.5;
                        b[i]=(byte)(v*80);
                    }
                    sdl.write(b,0,b.length); sdl.drain(); sdl.close();
                } catch(Exception e){}
            }).start();
        }
    }
}