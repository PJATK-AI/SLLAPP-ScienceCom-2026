/**
 * A rectangle of the camera image, normalized to its width and height (0–1).
 * The coordinates are in the original camera image, before any mirroring.
 */
export interface CameraCrop {
    x: number;
    y: number;
    width: number;
    height: number;
}
