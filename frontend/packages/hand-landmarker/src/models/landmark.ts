export interface Landmark {
    /** Horizontal position, normalized to the image width (0–1). */
    x: number;
    /** Vertical position, normalized to the image height (0–1). */
    y: number;
    /** Depth relative to the wrist. A smaller value is nearer to the camera. */
    z: number;
}
