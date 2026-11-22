// Kiosk-friendly nicknames: an adjective and an animal that agree in gender, at most 20 characters.
const feminine = ['SOWA', 'PANDA', 'WYDRA', 'FOKA', 'ŻABA', 'MRÓWKA', 'PSZCZOŁA', 'KOZA', 'LAMA'];
const masculine = ['LIS', 'JEŻ', 'BÓBR', 'KRUK', 'ŻÓŁW', 'DELFIN', 'BORSUK', 'OSIOŁ', 'PINGWIN'];
const adjectives: [string, string][] = [
    ['SZYBKI', 'SZYBKA'],
    ['CICHY', 'CICHA'],
    ['MĄDRY', 'MĄDRA'],
    ['ZWINNY', 'ZWINNA'],
    ['DZIELNY', 'DZIELNA'],
    ['BYSTRY', 'BYSTRA'],
    ['WESOŁY', 'WESOŁA'],
    ['SPRYTNY', 'SPRYTNA'],
];

function pick<T>(items: readonly T[], random: () => number): T {
    return items[Math.floor(random() * items.length)]!;
}

export function randomNickname(random: () => number = Math.random): string {
    const isFeminine = random() < 0.5;
    const [male, female] = pick(adjectives, random);
    const animal = pick(isFeminine ? feminine : masculine, random);
    return `${isFeminine ? female : male}_${animal}`.slice(0, 20);
}
