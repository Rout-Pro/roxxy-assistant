/**
 * OPTIONAL reference backend from the original ROXXY blueprint.
 * The shipped Android app does NOT need this server - it talks to Gemini
 * directly with the user's own key stored encrypted on the phone.
 * Run this only if you want a hosted brain: GEMINI_API_KEY=... node server.js
 */
import express from "express";
import cors from "cors";
import { GoogleGenAI } from "@google/genai";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
app.use(cors());
app.use(express.json({ limit: "30mb" }));

const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });

// Same persona prompt the app uses (kept in the Android source too).
const promptFile = path.join(__dirname, "ROXXY_PROMPT.txt");
const ROXXY_PROMPT = fs.existsSync(promptFile)
  ? fs.readFileSync(promptFile, "utf8")
  : "You are ROXXY, a Hinglish Android AI companion. Reply as JSON {speech, emotion, expectFollowUp, actions}.";

app.post("/api/roxxy/brain", async (req, res) => {
  try {
    const {
      userText, screenNodes = [], screenshotBase64 = null,
      deviceTelemetry = {}, recentNotifications = [],
      clipboardText = "", chatHistory = []
    } = req.body;

    const contents = [];
    chatHistory.slice(-10).forEach((turn) => {
      contents.push({ role: turn.role === "user" ? "user" : "model", parts: [{ text: turn.content }] });
    });

    const currentParts = [];
    if (screenshotBase64) {
      currentParts.push({ inlineData: { mimeType: "image/jpeg", data: screenshotBase64 } });
    }
    currentParts.push({ text: `
[ROXXY LIVE DEVICE TELEMETRY]:
- Battery: ${deviceTelemetry.battery ?? "Unknown"}% | Charging: ${deviceTelemetry.isCharging ?? false}
- Current Foreground App: ${deviceTelemetry.foregroundPackage ?? "Home Screen"}
- Clipboard Content: "${String(clipboardText).slice(0, 300)}"

[UNREAD / RECENT NOTIFICATIONS]:
${JSON.stringify(recentNotifications.slice(0, 10))}

[LIVE SCREEN UI NODES]:
${JSON.stringify(screenNodes.slice(0, 85))}

[BOSS VOICE / TEXT COMMAND]: "${userText}"
` });
    contents.push({ role: "user", parts: currentParts });

    const response = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents,
      config: { systemInstruction: ROXXY_PROMPT, responseMimeType: "application/json", temperature: 0.68 }
    });
    return res.json(JSON.parse(response.text));
  } catch (error) {
    console.error("Roxxy Brain Error:", error);
    return res.status(500).json({
      speech: "Boss, server connection mein thoda issue aa raha hai, ek second baad try karein?",
      emotion: "alert", expectFollowUp: false, actions: []
    });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`ROXXY AI server on port ${PORT}`));
