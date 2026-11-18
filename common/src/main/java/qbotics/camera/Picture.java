package qbotics.camera;

/** The kinds of creature a Qbot's view tells apart (what programs read as "monster", "animal"...). */
public final class Picture {
    private Picture() {
    }

    public static final int MONSTER = 0;
    public static final int ANIMAL = 1;
    public static final int PLAYER = 2;
    public static final int OTHER = 3;
}
