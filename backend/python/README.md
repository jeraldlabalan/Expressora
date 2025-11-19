# Expressora Python gRPC Server

Python gRPC server for the landmark streaming architecture.

## Setup

1. **Install dependencies:**
```bash
pip install -r requirements.txt
```

2. **Generate gRPC code from proto:**
```bash
# Copy proto file from Android app
cp ../app/src/main/proto/expressora.proto proto/

# Generate Python gRPC code
python -m grpc_tools.protoc -Iproto --python_out=server --grpc_python_out=server proto/expressora.proto
```

3. **Run server:**
```bash
python server/expressora_server.py --port 50051 --host 0.0.0.0
```

## Features

- **Bidirectional Streaming**: Receives landmark frames, returns translations
- **Max 7 Gloss Buffering**: Buffers up to 7 glosses before emitting
- **Silence/Pause Trigger (Tweak 1)**: Automatically emits when user stops signing (>2 seconds)
- **Suprasegmental Tone (Tweak 2)**: Returns sentence-level tone in addition to per-gloss tones
- **Mock Classifier**: Returns sample glosses for testing (replace with actual ML model in production)

## Configuration

Edit `expressora_server.py` to adjust:
- `max_glosses`: Maximum glosses to buffer (default: 7)
- `silence_threshold`: Seconds to wait before forcing emit (default: 2.0)
- `processing_delay`: Simulated processing time (default: 0.05s)

## Testing

Use the Android app to connect to the server. For emulator, use `10.0.2.2:50051`. For physical device, use your computer's IP address.

