/**
 * Local ambient declarations for the Node builtins the structural specs read through
 * (architecture.spec.ts walks the tree with node:fs). Vitest runs in Node, so the
 * modules exist at runtime; the project deliberately has no @types/node dependency,
 * so the tiny surface the specs use is declared here instead. If @types/node ever
 * becomes a dependency, this file can be deleted.
 */
declare module 'node:fs' {
  export interface DirentLike {
    name: string;
    isDirectory(): boolean;
    isFile(): boolean;
  }
  export function readdirSync(path: string, options: { withFileTypes: boolean }): DirentLike[];
  export function readFileSync(path: string, encoding: 'utf8'): string;
}

declare module 'node:path' {
  export function dirname(p: string): string;
  export function join(...segments: string[]): string;
  export function relative(from: string, to: string): string;
  export function resolve(...segments: string[]): string;
}

declare module 'node:url' {
  export function fileURLToPath(url: string | URL): string;
}
