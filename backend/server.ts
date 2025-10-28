import express, { Request, Response } from "express";
import bodyParser from "body-parser";
import cors from "cors";
import dotenv from "dotenv";
import nodemailer from "nodemailer";
import { OAuth2Client } from "google-auth-library";
import admin from "firebase-admin";

dotenv.config();
const app = express();
const PORT: number = Number(process.env.PORT) || 3000;

app.use(cors());
app.use(bodyParser.json());

// Initialize Firebase Admin SDK
if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.cert(require("./serviceAccountKey.json")),
  });
}
const db = admin.firestore();

// Nodemailer setup
const transporter = nodemailer.createTransport({
  service: "gmail",
  auth: {
    user: process.env.EMAIL_USER,
    pass: process.env.EMAIL_PASS,
  },
});

const googleClient = new OAuth2Client(process.env.GOOGLE_CLIENT_ID);

// OTP storage with timestamp
interface OtpEntry { code: string; createdAt: number }
const otpStore: { [email: string]: OtpEntry } = {};

// Root endpoint
app.get("/", (req, res) => res.send("Expressora Backend Running ✅"));

// Send OTP endpoint
app.post("/send-otp", async (req: Request, res: Response) => {
  const { email } = req.body;
  if (!email) return res.status(400).json({ success: false, message: "Email required" });

  try {
    // Check if user already exists
    const existingUser = await db.collection("users").where("email", "==", email).get();
    if (!existingUser.empty) {
      return res.status(409).json({ success: false, message: "Email already registered" });
    }

    // Generate OTP
    const otp = Math.floor(10000 + Math.random() * 90000).toString();
    otpStore[email] = { code: otp, createdAt: Date.now() };

    // Send email
    await transporter.sendMail({
      from: process.env.EMAIL_USER,
      to: email,
      subject: "Expressora Verification Code",
      text: `Your verification code is ${otp}. It expires in 5 minutes.`,
    });

    res.json({ success: true, message: "OTP sent successfully" });
  } catch (error) {
    console.error("Error sending OTP:", error);
    res.status(500).json({ success: false, message: "Failed to send OTP" });
  }
});

// Verify OTP endpoint
app.post("/verify-otp", (req: Request, res: Response) => {
  const { email, otp } = req.body;
  if (!email || !otp) return res.status(400).json({ success: false, message: "Email & OTP required" });

  const entry = otpStore[email];
  if (!entry) return res.status(400).json({ success: false, message: "OTP not found or expired" });

  // Check if OTP expired (5 minutes)
  if (Date.now() - entry.createdAt > 5 * 60 * 1000) {
    delete otpStore[email];
    return res.status(400).json({ success: false, message: "OTP expired" });
  }

  // Check if OTP matches
  if (entry.code === otp) {
    delete otpStore[email];
    return res.json({ success: true, message: "OTP verified successfully" });
  } else {
    return res.status(400).json({ success: false, message: "Invalid OTP" });
  }
});

// Google Auth endpoint
app.post("/google-auth", async (req: Request, res: Response) => {
  const { token } = req.body;
  if (!token) return res.status(400).json({ success: false, message: "Token required" });

  try {
    const ticket = await googleClient.verifyIdToken({
      idToken: token,
      audience: process.env.GOOGLE_CLIENT_ID,
    });
    const payload = ticket.getPayload();
    if (!payload) throw new Error("Invalid Google token");

    const { email, name, picture } = payload;
    return res.status(200).json({ success: true, user: { email, name, picture } });
  } catch (error: unknown) {
    if (error instanceof Error)
      return res.status(401).json({ success: false, message: error.message });
    return res.status(500).json({ success: false, message: "Internal server error" });
  }
});

// Optional: Periodic cleanup of expired OTPs every 10 minutes
setInterval(() => {
  const now = Date.now();
  for (const email in otpStore) {
    if (now - otpStore[email].createdAt > 5 * 60 * 1000) {
      delete otpStore[email];
    }
  }
}, 10 * 60 * 1000);

app.listen(PORT, "0.0.0.0", () => console.log(`Server running on port ${PORT}`));
