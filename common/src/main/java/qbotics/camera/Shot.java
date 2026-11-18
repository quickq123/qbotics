package qbotics.camera;

/**
 * Something a Qbot's camera made that a screen can show as image:name: a {@link View} (the faces
 * it can see, drawn in the player's game), kept by {@link Camera} under a number.
 */
public sealed interface Shot permits View {
    int width();

    int height();
}
