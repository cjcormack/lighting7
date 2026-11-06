import { runBoot } from './remoteBoot'

// The page's only script: remote mode is decided before `./main`, and so `lightingApi`, loads.
void runBoot(window, () => import('./main'))
