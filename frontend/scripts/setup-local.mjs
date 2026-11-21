import { constants } from 'node:fs';
import { copyFile } from 'node:fs/promises';

const example = new URL('../.env.example', import.meta.url);
const local = new URL('../apps/web/.env.local', import.meta.url);

try {
    await copyFile(example, local, constants.COPYFILE_EXCL);
} catch (error) {
    if (error.code !== 'EEXIST') throw error;
}
