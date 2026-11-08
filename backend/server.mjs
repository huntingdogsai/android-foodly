import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';

const products = [
  { id: 'p_chicken', name: 'Chicken Pot', price: 4000, inStock: true, category: 'Meals' },
  { id: 'p_coke', name: 'Coke', price: 500, inStock: true, category: 'Drinks' },
  { id: 'p_water', name: 'Water', price: 300, inStock: true, category: 'Drinks' },
  { id: 'p_chocolate', name: 'Chocolate Cake', price: 2500, inStock: false, category: 'Desserts' },
];

const sessions = new Map();
let orderCounter = 1000;

function stateFor(token) {
  if (!token || !sessions.has(token)) return null;
  return sessions.get(token);
}

function sessionFromRequest(request) {
  const authorization = request.headers.authorization ?? '';
  const match = authorization.match(/^Bearer (.+)$/);
  return stateFor(match?.[1]);
}

async function readJson(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  const text = Buffer.concat(chunks).toString('utf8').trim();
  return text ? JSON.parse(text) : {};
}

function writeJson(response, status, body = undefined) {
  response.statusCode = status;
  response.setHeader('content-type', 'application/json');
  if (body === undefined) {
    response.end();
    return;
  }
  response.end(JSON.stringify(body));
}

function userForEmail(email) {
  const firstName = email.split('@')[0].replace(/[^a-z0-9]+/gi, ' ').trim() || 'Demo User';
  const name = firstName
    .split(/\s+/)
    .map((word) => `${word[0].toUpperCase()}${word.slice(1)}`)
    .join(' ');
  return {
    id: `usr_${email.replace(/[^a-z0-9]+/gi, '_').toLowerCase()}`,
    name,
    email,
    loyaltyTier: 'Gold',
  };
}

function publicSession(session) {
  return {
    user: session.user,
    authenticated: true,
    tokenStored: true,
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
  };
}

function cartTotal(cart) {
  return cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
}

function productById(id) {
  return products.find((product) => product.id === id);
}

function reset() {
  sessions.clear();
  orderCounter = 1000;
}

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');

    if (request.method === 'GET' && url.pathname === '/healthz') {
      writeJson(response, 200, { ok: true, product: 'foodly-api' });
      return;
    }

    if (request.method === 'POST' && url.pathname === '/__qa/reset') {
      reset();
      writeJson(response, 200, { ok: true });
      return;
    }

    if (request.method === 'GET' && url.pathname === '/auth/session') {
      const session = sessionFromRequest(request);
      if (!session) {
        writeJson(response, 204);
        return;
      }
      writeJson(response, 200, publicSession(session));
      return;
    }

    if (request.method === 'POST' && url.pathname === '/auth/sign-in') {
      const body = await readJson(request);
      const email = String(body.email ?? '').trim().toLowerCase();
      const password = String(body.password ?? '');
      if (!email.includes('@') || password.length < 4) {
        writeJson(response, 401, { error: 'Invalid credentials' });
        return;
      }
      const accessToken = `foodly-access-${randomUUID()}`;
      const refreshToken = `foodly-refresh-${randomUUID()}`;
      const user = userForEmail(email);
      const session = {
        accessToken,
        refreshToken,
        user,
        cart: [],
        draft: {
          fullName: user.name,
          email: user.email,
          phone: '',
          address: '',
          deliveryInstructions: '',
          deliveryWindow: 'Today 6-8 PM',
          paymentMethod: 'Card on delivery',
          promoCode: '',
          saveAddress: true,
        },
      };
      sessions.set(accessToken, session);
      writeJson(response, 200, publicSession(session));
      return;
    }

    if (request.method === 'POST' && url.pathname === '/auth/sign-out') {
      const session = sessionFromRequest(request);
      if (session) sessions.delete(session.accessToken);
      writeJson(response, 200, { ok: true });
      return;
    }

    if (request.method === 'GET' && url.pathname === '/products/search') {
      const q = String(url.searchParams.get('q') ?? '').trim().toLowerCase();
      const results = q
        ? products.filter((product) => product.name.toLowerCase().includes(q))
        : products;
      writeJson(response, 200, { products: results });
      return;
    }

    if (request.method === 'POST' && url.pathname === '/cart/items') {
      const session = sessionFromRequest(request);
      if (!session) {
        writeJson(response, 401, { error: 'Sign in is required' });
        return;
      }
      const body = await readJson(request);
      const product = productById(String(body.productId ?? ''));
      if (!product) {
        writeJson(response, 404, { error: 'Product was not found' });
        return;
      }
      if (!product.inStock) {
        writeJson(response, 409, { error: 'Product is out of stock' });
        return;
      }
      const quantity = Math.max(1, Number.parseInt(String(body.quantity ?? 1), 10) || 1);
      session.cart = session.cart.filter((item) => item.productId !== product.id);
      session.cart.push({
        productId: product.id,
        name: product.name,
        price: product.price,
        quantity,
      });
      writeJson(response, 200, { items: session.cart, total: cartTotal(session.cart) });
      return;
    }

    if (request.method === 'PATCH' && url.pathname === '/checkout/draft') {
      const session = sessionFromRequest(request);
      if (!session) {
        writeJson(response, 401, { error: 'Sign in is required' });
        return;
      }
      session.draft = { ...session.draft, ...(await readJson(request)) };
      writeJson(response, 200, { draft: session.draft });
      return;
    }

    if (request.method === 'POST' && url.pathname === '/promotions/validate') {
      const session = sessionFromRequest(request);
      if (!session) {
        writeJson(response, 401, { error: 'Sign in is required' });
        return;
      }
      const body = await readJson(request);
      const code = String(body.code ?? '').trim().toUpperCase();
      const subtotal = Number(body.subtotal ?? 0);
      const discount = code === 'SAVE10' ? Math.round(subtotal * 0.1) : 0;
      if (discount <= 0) {
        writeJson(response, 404, { code, discount: 0, valid: false, error: 'Promo code is not valid' });
        return;
      }
      writeJson(response, 200, { code, discount, valid: true });
      return;
    }

    if (request.method === 'POST' && url.pathname === '/orders') {
      const session = sessionFromRequest(request);
      if (!session) {
        writeJson(response, 401, { error: 'Sign in is required' });
        return;
      }
      const body = await readJson(request);
      const draft = body.draft ?? {};
      if (!Array.isArray(body.items) || body.items.length === 0) {
        writeJson(response, 409, { error: 'Cart is empty' });
        return;
      }
      if (String(draft.phone ?? '').trim().length < 6 || String(draft.address ?? '').trim().length < 6) {
        writeJson(response, 422, { error: 'Delivery details are incomplete' });
        return;
      }
      const subtotal = body.items.reduce((sum, item) => sum + Number(item.price) * Number(item.quantity), 0);
      const discount = Number(body.discount ?? 0);
      const order = {
        id: `ord_${++orderCounter}`,
        items: body.items,
        subtotal,
        discount,
        total: subtotal - discount,
        delivery: draft,
        status: 'confirmed',
      };
      writeJson(response, 201, order);
      return;
    }

    writeJson(response, 404, { error: 'Not found' });
  } catch (error) {
    writeJson(response, 500, { error: error instanceof Error ? error.message : String(error) });
  }
});

const portArg = process.argv.find((arg) => arg.startsWith('--port='));
const port = portArg ? Number(portArg.split('=')[1]) : Number(process.env.PORT || 8787);
server.listen(port, process.env.PORT ? '0.0.0.0' : '127.0.0.1', () => {
  const address = server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;
  console.log(`[foodly-api] listening http://127.0.0.1:${actualPort}`);
});

process.on('SIGINT', () => {
  server.close(() => process.exit(0));
});
