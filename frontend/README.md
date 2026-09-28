# Lighting Controller Frontend

React frontend for Chris' DMX Controller v7 - a web-based interface for controlling DMX lighting fixtures.

## Features

- **Script Editor**: Write and test Kotlin lighting scripts with syntax highlighting and in-browser compilation
- **Scenes & Chases**: Configure and trigger lighting scenes with customizable settings
- **Fixture Management**: Define and manage DMX fixtures
- **Channel Control**: Direct control of individual DMX channels per universe
- **Real-time Updates**: WebSocket-based live status and channel value updates

## Requirements

- Node.js 18+
- [lighting7 backend](../lighting7) running on port 8413

## Getting Started

```bash
# Install dependencies
npm install

# Start development server
npm run dev
```

The app opens at http://localhost:5173 and proxies API requests to the backend at http://localhost:8413.

## Available Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start development server with hot reload |
| `npm run build` | Build for production |
| `npm run preview` | Preview production build |
| `npm run lint` | Run ESLint |
| `npm run lint:fix` | Fix ESLint issues |
| `npm run format` | Format code with Prettier |
| `npm run type-check` | TypeScript type checking |

## Tech Stack

- React 19 + TypeScript
- Vite
- Radix UI primitives + Tailwind
- Redux Toolkit + RTK Query
- React Router v8

## Project Structure

```
src/
├── api/           # WebSocket and REST API layer
├── store/         # Redux store and RTK Query slices
├── routes/        # Page components
├── App.tsx        # Router setup
├── Layout.tsx     # Main layout with navigation
└── main.tsx       # Entry point
```

## Documentation

- [Show Mode Engineering](docs/show-mode-engineering.md) -- architecture, data model, and playback flow for the show programming and running feature

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `VITE_SOCKET_URL` | WebSocket URL override | Auto-detected from host |

## Backend

This frontend requires the [lighting7](../lighting7) Kotlin backend to be running. The backend handles:

- DMX output to physical lighting hardware
- Kotlin script compilation and execution
- Fixture, scene, and script persistence
- WebSocket message routing