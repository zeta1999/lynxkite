'''Creates a node embedding using PyTorch Geometric.'''
import numpy as np
import torch
from torch.utils.data import DataLoader
from torch_geometric.nn import Node2Vec
from . import util

op = util.Op()
device = 'cuda' if torch.cuda.is_available() else 'cpu'
print(f'node2vec running on {device}')
num_nodes = op.input_arrow('vs').length()
print('num_nodes:', num_nodes)
es = op.input('es')

# We need to add loop edges for vertices with no outgoing edges.
# https://github.com/rusty1s/pytorch_cluster/issues/45
index, counts = np.unique(es.src, return_counts=True)
degree = np.zeros(num_nodes)
degree[index] = counts
deadends = (degree == 0).nonzero()[0]
# Also convert from uint32 to int64 for PyTorch.
srcs = np.concatenate((es.src, deadends)).astype('int64')
dsts = np.concatenate((es.dst, deadends)).astype('int64')

# Configure Node2Vec.
edges = torch.tensor([srcs, dsts])
loader = DataLoader(torch.arange(num_nodes), batch_size=128, shuffle=True)
model = Node2Vec(
    num_nodes, embedding_dim=op.params['dimensions'], walk_length=op.params['walkLength'],
    context_size=op.params['contextSize'], walks_per_node=op.params['walksPerNode'])
model, edges = model.to(device), edges.to(device)
optimizer = torch.optim.Adam(model.parameters(), lr=0.01)

# Train model.
for epoch in range(op.params['iterations']):
  model.train()
  total_loss = 0
  for subset in loader:
    optimizer.zero_grad()
    loss = model.loss(edges, subset.to(device))
    loss.backward()
    optimizer.step()
    total_loss += loss.item()
  print('epoch', epoch, 'loss', total_loss / len(loader))

# Generate and write output.
model.eval()
with torch.no_grad():
  z = model(torch.arange(num_nodes, device=device))
op.output('embedding', z, type=util.DoubleVectorAttribute)
