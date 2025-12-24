# =================================================================
#  FINAL SERVER (DEBUG MODE: VISIBLE OUTPUT)
# =================================================================
import os
import sys
import subprocess
import pickle
import json
import warnings
import time

# --- 1. SILENCE WARNINGS ---
warnings.filterwarnings("ignore", category=RuntimeWarning)

# --- 2. INSTALLATION ---
print("⏳ Installing pyngrok and dependencies...")
subprocess.run("pip install -q pyngrok dm-haiku optax fastapi uvicorn nest_asyncio python-multipart", shell=True)

# --- 3. CONFIGURATION ---
import nest_asyncio
nest_asyncio.apply()

from pyngrok import ngrok, conf
import uvicorn
from fastapi import FastAPI, Request
import jax
import haiku as hk

MY_TOKEN = "YOUR NGROK TOKEN"
STATIC_DOMAIN = "YOUR NGROK DOMAIN"

# --- 4. CLONE REPO ---
print("⏳ Cloning DeepMind Repo...")
if os.path.exists("predictingthepast"):
    import shutil
    shutil.rmtree("predictingthepast")
subprocess.run("git clone https://github.com/google-deepmind/predictingthepast.git", shell=True)
sys.path.append(os.path.abspath("predictingthepast"))

from predictingthepast.models.model import Model
from predictingthepast.util import alphabet as util_alphabet
from predictingthepast.eval import inference

# --- 5. LOAD BRAIN ---
print("🔍 Finding Checkpoint...")
CHECKPOINT_PATH = None
for root, dirs, files in os.walk("/kaggle/input"):
    for file in files:
        if "aeneas" in file.lower() and file.endswith(".pkl") and "emb" not in file:
            CHECKPOINT_PATH = os.path.join(root, file); break

if not CHECKPOINT_PATH: sys.exit("❌ Error: Aeneas .pkl file not found.")

print(f"🔧 Loading Brain from {os.path.basename(CHECKPOINT_PATH)}...")
with open(CHECKPOINT_PATH, 'rb') as f:
    checkpoint = pickle.load(f)

model_config = checkpoint['model_config']
params = jax.device_put(checkpoint['params'])
model_obj = Model(**model_config)
forward_fn = model_obj.apply
alphabet = util_alphabet.LatinAlphabet()
vocab_char_size = model_config['vocab_char_size']
print("   ✅ BRAIN LOADED (Ready for fast inference)")

# --- 6. START SERVER ---
print("🔌 Starting Server...")
ngrok.kill()
conf.get_default().auth_token = MY_TOKEN
ngrok.set_auth_token(MY_TOKEN)

app = FastAPI()

@app.post("/restore")
async def handle_request(request: Request):
    try:
        data = await request.json()
        text = data.get("text", "")
        print(f"\n📝 RECEIVED INPUT: {text[:50]}...") # Print start of input
        
        if not text:
            print("   ⚠️ Empty input received.")
            return {"prediction": ""}

        print("   🔄 Inference Started... (Please Wait)")
        
        # OFFICIAL RESTORATION FUNCTION
        restoration = inference.restore(
            text,
            forward=forward_fn,
            params=params,
            alphabet=alphabet,
            vocab_char_size=vocab_char_size,
            beam_width=5,
            temperature=1.0,
            unk_restoration_max_len=15
        )
        
        # Parse result
        res_json = restoration.json()
        if isinstance(res_json, str): res_json = json.loads(res_json)
        prediction = res_json.get("predictions", ["No result"])[0]
        
        # --- THE MISSING LOG ---
        print(f"   ✅ Inference Finished!")
        print(f"   📤 SENDING TO PHONE: {prediction}") 
        # ---------------------
        
        return {"prediction": prediction}
        
    except Exception as e:
        print(f"   ❌ SERVER ERROR: {e}")
        return {"prediction": f"Error: {str(e)}"}

# Connect to Static Domain
try:
    tunnel = ngrok.connect(8000, domain=STATIC_DOMAIN)
    print(f"\n✅ SERVER ONLINE: {tunnel.public_url}/restore")
except Exception as e:
    print(f"\n❌ Static Domain Error: {e}")
    fallback = ngrok.connect(8000).public_url
    print(f"   ✅ FALLBACK URL: {fallback}/restore")

config = uvicorn.Config(app, port=8000, log_level="error") # Hide technical logs, show my logs
server = uvicorn.Server(config)
await server.serve()