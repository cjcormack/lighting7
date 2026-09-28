// The stock node resolver, taught the `@/` alias that vite.config.ts and tsconfig.json both map
// to `src/`.
//
// This is load-bearing rather than a nicety. `import/no-cycle` silently skips any import it
// cannot resolve, and roughly seven in ten imports in this tree are written `@/…` rather than
// relatively — so configured with the bare node resolver the rule reports nothing, passes
// forever, and catches none of the cycles it exists to catch. That is worse than having no rule,
// because it looks like one. Verified by temporarily reintroducing a cycle across both import
// styles and checking that both are reported.
//
// A separate module rather than an object in eslint.config.js because eslint-module-utils'
// `requireResolver` takes a module *name* and `require`s it; an inline object is not something it
// can load. If this ever needs to understand more of the tsconfig than one prefix,
// `eslint-import-resolver-typescript` reads `paths` directly and should replace it wholesale.

const path = require('node:path')
const nodeResolver = require('eslint-import-resolver-node')

const SRC = path.join(__dirname, 'src')
const EXTENSIONS = ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.json']

exports.interfaceVersion = 2

exports.resolve = function resolve(source, file, config) {
  const target = source.startsWith('@/') ? path.join(SRC, source.slice(2)) : source
  return nodeResolver.resolve(target, file, { extensions: EXTENSIONS, ...config })
}
