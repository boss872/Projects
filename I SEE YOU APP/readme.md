# I See You

ICU bed finder and booking app for India

Some people are not able to locate available ICU beds on time, often leading to preventable deaths. I See You helps a person in a medical emergency, anywhere in India, find the nearest hospital with an available ICU bed and book it instantly, all from their phone.

## Features

- Real-time ICU bed availability search using GPS or manual location entry (address, pincode, or city)
- List, map, and virtual radar views of nearby hospitals, sorted by distance, time, or bed availability
- One-tap ICU bed booking with an automatic hold and expiry system so beds are never double-booked
- Hospital self-registration and a bed management dashboard for hospital staff to keep availability current
- Backend watchdog that reminds hospitals to update bed counts, and flags stale listings in the app
- In-app AI assistant for instant, conversational help navigating the app, checking bed counts, and booking
- Google Maps integration with routing to the nearest hospital that actually has an available ICU bed
- Card payment (debit or credit) for the booking fee, verified in real time through a licensed payment gateway

## Tech Stack

Frontend: React, Tailwind CSS, and shadcn/ui

Backend: Supabase, covering Postgres, Auth, and Edge Functions

Maps: Google Maps API, with Leaflet and OpenStreetMap as a free fallback

AI Assistant: Gemini API

Payments: Razorpay, card only

## Current Status

- Prototype or MVP stage. Hospital data is seed and demo data, not live hospital integrations yet
- Location detection and search is inconsistent across cities. Better coverage in major metros, less reliable in smaller towns
- Payment flow is built and tested in sandbox mode only
- Not affiliated with hospitals or emergency services. Always call 108 in a real emergency
- Location-based search requires GPS or manual entry. Accuracy depends on device signal

## Disclaimer

ICU bed availability shown in this app is updated periodically and may not reflect real-time hospital status until direct hospital integrations go live. In a medical emergency, always call your hospital directly and dial 108, India's national ambulance service, at the same time as using this app.
