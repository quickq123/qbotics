package qbotics.camera;

/**
 * Something a Qbot's camera made that a screen can show as image:name: a {@link Picture} (one
 * ray a pixel, coloured in the player's game) or a {@link View} (the faces it can see, drawn in
 * the player's game). Both are kept by {@link Camera} under one run of numbers.
 */
public sealed interface Shot permits Picture, View {
    int width();

    int height();
}
